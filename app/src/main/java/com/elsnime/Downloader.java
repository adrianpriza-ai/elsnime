package com.elsnime;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.util.Log;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.muxer.BufferInfo;
import androidx.media3.muxer.FileOutputStreamSeekableMuxerOutput;
import androidx.media3.muxer.Mp4Muxer;
import androidx.media3.muxer.Muxer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Downloads an anime episode (HLS, from {@link AniDbScraper#stream}) into the
 * public Movies/Elsnime/&lt;anime&gt;/ folder. MPEG-TS streams are remuxed to
 * .mp4 by the media3 {@code Mp4Muxer} (a pure-Java writer) — the system
 * MediaMuxer is never used, because its native MPEG4Writer aborts the entire
 * process on some devices (a C++ crash Java can't catch). When a stream
 * can't be muxed (exotic codecs, >4 GB output), the raw .ts is kept instead,
 * so the download still succeeds. fMP4 streams (HLS with an
 * {@code #EXT-X-MAP} init segment) are already MP4, so those are downloaded
 * by plain concatenation and stored as .mp4 with no conversion either.
 *
 * <p>Storage: on API 29+ files are written through MediaStore (no permission
 * needed, visible in the Files app under Movies/Elsnime); on API 23-28 they go
 * to Environment.DIRECTORY_MOVIES/Elsnime and require WRITE_EXTERNAL_STORAGE,
 * which MainActivity requests before the first download.
 *
 * <p>Work is queued on a single worker thread so a full-series download
 * politely downloads one episode at a time. Progress/state is pushed to the
 * frontend through the {@link Listener} as JSON events.
 */
final class Downloader {
    interface Listener { void onEvent(JSONObject event); }

    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124.0.0.0 Safari/537.36";
    private static final String RELATIVE_ROOT = "Movies/Elsnime/";

    private final Context ctx;
    private final AniDbScraper scraper;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Map<String, Task> tasks = new ConcurrentHashMap<>(); // "animeId|episode" -> Task
    private final Map<String, JSONObject> lastEvent = new ConcurrentHashMap<>(); // "animeId|episode" -> last emitted event
    private final SharedPreferences prefs;

    Downloader(Context c, AniDbScraper s, Listener l) {
        ctx = c.getApplicationContext();
        scraper = s;
        listeners.add(l);
        prefs = ctx.getSharedPreferences("downloads", Context.MODE_PRIVATE);
        // Downloads interrupted by a process death (force-stop, crash, kill)
        // resume from their persisted records + partial temp files.
        resumeInterrupted();
        // Leftover .part/.mp4 temps from a killed or crashed run accumulate in
        // the cache forever. This runs at app start; every temp not claimed by
        // a resumed download is garbage — sweep them all.
        sweepTemps(0);
    }

    /** Extra event listeners (e.g. DownloadService's progress notification). */
    void addListener(Listener l) { listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    /** Tasks queued or running; used by DownloadService to know when to stop. */
    int activeCount() { return tasks.size(); }

    private static final class Task {
        final String uid, animeId, animeTitle, episode, type, quality, key;
        volatile boolean cancelled;
        volatile Resume resume; // set when re-enqueued after an interruption
        Task(String uid, String animeId, String animeTitle, String episode, String type, String quality) {
            this.uid = uid; this.animeId = animeId; this.animeTitle = animeTitle;
            this.episode = episode; this.type = type; this.quality = quality;
            this.key = animeId + "|" + episode;
        }
    }

    /** Where a partial download left off (loaded from the persisted record). */
    private static final class Resume {
        int segmentsDone; // segments fully in the .part file
        long bytesDone;   // file length at that boundary
        boolean initDone; // fMP4 init segment already written
    }

    /** Enqueue a download (no-op if one for this anime+episode is already active). */
    void start(JSONObject req) {
        Task t = new Task(
            req.optString("uid"), req.optString("anime_id"), req.optString("anime_title"),
            req.optString("episode"), req.optString("type", "sub"), req.optString("quality", "auto"));
        if (tasks.putIfAbsent(t.key, t) != null) return;
        // Persist before the worker starts: a death while queued/starting must
        // still be resumed (or re-downloaded) on the next launch.
        persistTask(t, "downloading", 0, 0, 0, false);
        worker.execute(() -> run(t));
    }

    boolean isActive(String key) { return tasks.containsKey(key); }

    void cancel(String key) {
        Task t = tasks.get(key);
        if (t != null) t.cancelled = true;
        // A cancelled download must not be resumed on the next launch (and the
        // in-persistTask cancelled check prevents it from coming back).
        clearTaskRecord(key);
    }

    /** Current state of every active task — lets the frontend re-sync after a
     *  restart, when events emitted before the page loaded were lost. */
    JSONArray activeDownloads() {
        JSONArray out = new JSONArray();
        for (JSONObject ev : lastEvent.values()) out.put(ev);
        return out;
    }

    /** Look up an already-downloaded file for an episode; null when missing. */
    JSONObject findFile(String animeTitle, String episode) {
        String dir = sanitizeTitle(animeTitle);
        String base = dir + " - Episode " + episode;
        if (Build.VERSION.SDK_INT >= 29) {
            String relDir = RELATIVE_ROOT + dir + "/";
            for (String ext : new String[]{".mp4", ".ts"}) {
                JSONObject f = queryFile(relDir, base + ext);
                if (f != null) return f;
            }
            return null;
        }
        File dirF = publicDir(dir);
        for (String ext : new String[]{".mp4", ".ts"}) {
            File f = new File(dirF, base + ext);
            if (f.exists() && f.isFile()) {
                try { return new JSONObject().put("fileName", f.getName()).put("dirName", dir).put("size", f.length()); }
                catch (Exception ignored) {}
            }
        }
        return null;
    }

    /** Delete a completed download (and stop any active one). Returns true if a file was removed. */
    boolean deleteFile(String animeTitle, String episode) {
        try {
            String dir = sanitizeTitle(animeTitle);
            String base = dir + " - Episode " + episode;
            if (Build.VERSION.SDK_INT >= 29) {
                String relDir = RELATIVE_ROOT + dir + "/";
                for (String ext : new String[]{".mp4", ".ts"}) {
                    Uri uri = queryUri(relDir, base + ext);
                    if (uri != null) return ctx.getContentResolver().delete(uri, null, null) > 0;
                }
                return false;
            }
            File dirF = publicDir(dir);
            for (String ext : new String[]{".mp4", ".ts"}) {
                File f = new File(dirF, base + ext);
                if (f.exists()) return f.delete();
            }
            return false;
        } catch (Exception ignored) { return false; }
    }

    /** Cancel every active download and delete all files under Movies/Elsnime
     *  (Settings > Remove all downloads). Returns the number of files removed. */
    int deleteAll() {
        for (Task t : tasks.values()) t.cancelled = true;
        // Everything is being removed — no download may be resumed afterwards.
        prefs.edit().clear().commit();
        int deleted = 0;
        if (Build.VERSION.SDK_INT >= 29) {
            try (Cursor c = ctx.getContentResolver().query(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    new String[]{MediaStore.Video.Media._ID},
                    MediaStore.Video.Media.RELATIVE_PATH + " LIKE ?",
                    new String[]{RELATIVE_ROOT + "%"}, null)) {
                if (c != null) while (c.moveToNext()) {
                    Uri uri = Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, String.valueOf(c.getLong(0)));
                    try { if (ctx.getContentResolver().delete(uri, null, null) > 0) deleted++; } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}
        } else {
            File root = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "Elsnime");
            File[] dirs = root.listFiles();
            if (dirs != null) for (File d : dirs) {
                if (!d.isDirectory()) continue;
                File[] fs = d.listFiles();
                if (fs != null) for (File f : fs) if (f.isFile() && f.delete()) deleted++;
                d.delete(); // sweep the now-empty per-anime dirs too
            }
        }
        // Also free the downloader's working storage: every temp file in the
        // app cache, except the ones an active (just-cancelled) task is still
        // writing — those clean themselves up when the worker notices.
        Set<String> active = new HashSet<>();
        for (Task t : tasks.values()) { active.add(t.uid + ".part"); active.add(t.uid + ".mp4"); }
        File[] temps = ctx.getCacheDir().listFiles();
        if (temps != null) for (File f : temps) {
            String n = f.getName();
            if ((n.endsWith(".part") || n.endsWith(".mp4")) && !active.contains(n) && f.isFile() && f.delete()) deleted++;
        }
        return deleted;
    }

    /** All files currently in Movies/Elsnime (for the Downloads tab reconciliation). */
    JSONArray listFiles() {
        JSONArray out = new JSONArray();
        if (Build.VERSION.SDK_INT >= 29) {
            try (Cursor c = ctx.getContentResolver().query(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    new String[]{MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.RELATIVE_PATH, MediaStore.Video.Media.SIZE},
                    MediaStore.Video.Media.RELATIVE_PATH + " LIKE ?",
                    new String[]{RELATIVE_ROOT + "%"}, null)) {
                if (c != null) while (c.moveToNext()) {
                    String name = c.getString(0);
                    String rel = c.getString(1);
                    try {
                        out.put(new JSONObject()
                            .put("fileName", name)
                            .put("dirName", rel.startsWith(RELATIVE_ROOT) ? rel.substring(RELATIVE_ROOT.length()).replace("/", "") : rel)
                            .put("size", c.getLong(2)));
                    } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}
        } else {
            File root = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "Elsnime");
            File[] dirs = root.listFiles();
            if (dirs != null) for (File d : dirs) {
                if (!d.isDirectory()) continue;
                File[] fs = d.listFiles();
                if (fs == null) continue;
                for (File f : fs) {
                    if (!f.isFile()) continue;
                    try { out.put(new JSONObject().put("fileName", f.getName()).put("dirName", d.getName()).put("size", f.length())); }
                    catch (Exception ignored) {}
                }
            }
        }
        return out;
    }

    // -run

    private void run(Task t) {
        File tmp = new File(ctx.getCacheDir(), t.uid + ".part");
        // Drop any stale temp with the same uid — older builds could leave a
        // .mp4 remux output behind after a crash.
        new File(ctx.getCacheDir(), t.uid + ".mp4").delete();
        Resume resume = t.resume;
        try {
            emit(t, "resolving", null, 0, 0, 0, "");
            if (t.cancelled) { emit(t, "cancelled", null, 0, 0, 0, ""); return; }

            JSONObject stream = scraper.stream(t.animeId, t.episode, t.type);
            if (t.cancelled) { emit(t, "cancelled", null, 0, 0, 0, ""); return; }
            if (stream.has("error")) { emit(t, "error", null, 0, 0, 0, stream.optString("error")); return; }

            String referer = stream.optString("referer");
            String master = stream.optString("master");
            String url = stream.optString("url");
            if (url.isEmpty()) { emit(t, "error", null, 0, 0, 0, "No stream available"); return; }

            int target = parseHeight(t.quality);
            // User-chosen quality: pick a rendition from the master playlist
            // (the scraper's stream.url is the best variant; downloads honor
            // the Default Quality setting like the player's menu does).
            if (target > 0 && !master.isEmpty()) {
                try {
                    String mm = fetchText(master, referer);
                    if (mm.contains("#EXT-X-STREAM-INF")) {
                        String v = AniDbScraper.pickVariant(master, mm, target);
                        if (!v.isEmpty()) url = v;
                    }
                } catch (Exception ignored) {}
            }

            String manifest = fetchText(url, referer);
            // A variant playlist can itself be a master (rare) — follow once.
            if (manifest.contains("#EXT-X-STREAM-INF")) {
                String v = AniDbScraper.pickVariant(url, manifest, target > 0 ? target : 0);
                if (!v.isEmpty()) { url = v; manifest = fetchText(url, referer); }
            }
            String dir = sanitizeTitle(t.animeTitle);
            String base = dir + " - Episode " + t.episode;

            if (!manifest.contains("#EXTM3U")) {
                // Not a playlist: a single-file stream — just fetch it.
                boolean ok = downloadSegments(t, null, Collections.singletonList(url), null, null, 0, referer, tmp, resume);
                if (!ok) { tmp.delete(); emit(t, "cancelled", null, 0, 0, 0, ""); return; }
                // A direct .mp4 (starts with an ftyp box) is stored as-is —
                // no pointless (and crash-prone) remux of an already-MP4 file.
                storeAndFinish(t, tmp, dir, base, looksLikeMp4(tmp));
                return;
            }

            // Parse the media playlist: segment URIs + optional AES-128 key.
            List<String> segments = new ArrayList<>();
            String keyUrl = null;
            String initUrl = null; // #EXT-X-MAP init segment (fMP4 streams)
            byte[] iv = null;
            long mediaSeq = 0;
            for (String line : manifest.split("\n")) {
                line = line.trim();
                if (line.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                    try { mediaSeq = Long.parseLong(line.substring(line.indexOf(':') + 1).trim()); } catch (Exception ignored) {}
                } else if (line.startsWith("#EXT-X-MAP:")) {
                    // fMP4 init segment: the media fragments are already MP4,
                    // so the download is init + plain concatenation — no
                    // MediaMuxer. BYTERANGE MAPs (init embedded in a shared
                    // file) aren't supported and are refused below.
                    Matcher um = Pattern.compile("URI=\"([^\"]+)\"").matcher(line);
                    if (um.find() && !line.contains("BYTERANGE=")) initUrl = absoluteUrl(url, um.group(1));
                } else if (line.startsWith("#EXT-X-KEY:")) {
                    Matcher m = Pattern.compile("METHOD=([^,]+)").matcher(line);
                    if (m.find()) {
                        String method = m.group(1).trim();
                        if ("NONE".equalsIgnoreCase(method)) {
                            keyUrl = null; // encryption reset — later segments are plain
                        } else if ("AES-128".equalsIgnoreCase(method)) {
                            Matcher u = Pattern.compile("URI=\"([^\"]+)\"").matcher(line);
                            if (u.find()) keyUrl = absoluteUrl(url, u.group(1));
                            Matcher ivm = Pattern.compile("IV=0x([0-9a-fA-F]+)").matcher(line);
                            if (ivm.find()) {
                                String hex = ivm.group(1);
                                if (hex.length() == 32) {
                                    byte[] b = new byte[16];
                                    for (int k = 0; k < 16; k++) b[k] = (byte) Integer.parseInt(hex.substring(k * 2, k * 2 + 2), 16);
                                    iv = b;
                                }
                            }
                        }
                    }
                } else if (!line.startsWith("#") && !line.isEmpty()) {
                    segments.add(absoluteUrl(url, line));
                }
            }
            if (segments.isEmpty()) { emit(t, "error", null, 0, 0, 0, "No playable segments found"); return; }
            // An fMP4 stream whose init segment can't be fetched directly
            // (e.g. BYTERANGE) is the only format we still can't assemble.
            if (manifest.contains("#EXT-X-MAP") && initUrl == null) {
                emit(t, "error", null, 0, 0, 0, "This stream's format can't be downloaded");
                return;
            }

            boolean ok;
            if (initUrl != null) {
                // fMP4: already MP4, just concatenate — no conversion.
                ok = downloadSegments(t, initUrl, segments, keyUrl, iv, mediaSeq, referer, tmp, resume);
                if (!ok) { tmp.delete(); emit(t, "cancelled", null, 0, 0, 0, ""); return; }
                storeAndFinish(t, tmp, dir, base, true);
                return;
            }
            ok = downloadSegments(t, null, segments, keyUrl, iv, mediaSeq, referer, tmp, resume);
            if (!ok) { tmp.delete(); emit(t, "cancelled", null, 0, 0, 0, ""); return; }
            storeAndFinish(t, tmp, dir, base, false);
        } catch (Throwable e) {
            // Throwable, not just Exception: an OutOfMemoryError from a bogus
            // sample size (or any other Error) must degrade to a clean error
            // event instead of taking the whole app down.
            tmp.delete();
            if (!t.cancelled) {
                String msg = e.getMessage();
                emit(t, "error", null, 0, 0, 0, (msg == null || msg.isEmpty()) ? "Download failed" : msg);
            }
        } finally {
            tasks.remove(t.key);
            lastEvent.remove(t.key);
            clearTaskRecord(t.key);
        }
    }

    /** Concurrent segment fetches — enough to hide per-request latency, but
     *  each in-flight segment is held in RAM, so the window stays small. */
    private static final int FETCH_WINDOW = 3;

    /** Cap for a single fetched object (HLS segments are a few MB; a bogus
     *  huge response must not balloon into a multi-GB allocation). */
    private static final long MAX_SEGMENT_BYTES = 32L * 1024 * 1024;

    private boolean downloadSegments(Task t, String initUrl, List<String> segments, String keyUrl, byte[] iv, long mediaSeq, String referer, File tmp, Resume resume) throws IOException {
        byte[] key = null;
        if (keyUrl != null && !keyUrl.isEmpty()) {
            key = fetchBytes(keyUrl, referer);
            if (key == null || key.length != 16) throw new IOException("Could not load the stream's decryption key");
        }
        int total = segments.size();
        // Resume: continue from the persisted boundary when the partial file
        // still matches it (same playlist length, file not shorter than the
        // boundary). Anything else — no record, changed playlist, truncated
        // temp — means starting over from segment 0.
        int skip = 0;
        long bytes = 0;
        boolean initInFile = false;
        if (resume != null && resume.segmentsDone >= 0 && resume.segmentsDone <= total && tmp.length() >= resume.bytesDone) {
            skip = resume.segmentsDone;
            bytes = resume.bytesDone;
            initInFile = resume.initDone;
            if (bytes > 0) truncate(tmp, bytes); // drop a partial trailing segment
        }
        // fMP4 only: the #EXT-X-MAP init segment (ftyp+moov) must precede the
        // media fragments for the concatenation to be a valid MP4. It's only
        // in the file when the record says so; otherwise restart from zero.
        // (The init is stored as-is, never decrypted: these sources don't
        // encrypt it, and decryptSegment's TS-specific IV strip would corrupt
        // a legitimately-encrypted one.)
        if (initUrl != null && !initInFile) { skip = 0; bytes = 0; }
        emit(t, "downloading", null, skip, total, bytes, "");
        // Segments are fetched on a small pool but appended in playlist order
        // (TS/fMP4 concatenation is order-sensitive).
        ExecutorService pool = Executors.newFixedThreadPool(FETCH_WINDOW);
        try (FileOutputStream out = new FileOutputStream(tmp, bytes > 0)) {
            if (initUrl != null && !initInFile) {
                if (t.cancelled) return false;
                byte[] init = fetchSegment(initUrl, referer);
                if (t.cancelled) return false;
                out.write(init);
                bytes += init.length;
                initInFile = true;
                persistTask(t, "downloading", 0, total, bytes, true);
            }
            List<Future<byte[]>> inflight = new ArrayList<>();
            int next = skip;
            while (next < total || !inflight.isEmpty()) {
                // Keep up to FETCH_WINDOW fetches in flight.
                while (inflight.size() < FETCH_WINDOW && next < total) {
                    final int i = next++;
                    inflight.add(pool.submit(() -> t.cancelled ? null : fetchSegment(segments.get(i), referer)));
                }
                // Always take the oldest in-flight segment, in order.
                Future<byte[]> head = inflight.remove(0);
                int idx = next - inflight.size() - 1; // head's index in the playlist
                byte[] data;
                try {
                    data = head.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while downloading", e);
                } catch (ExecutionException e) {
                    throw new IOException("Could not fetch a segment", e.getCause());
                }
                if (t.cancelled || data == null) return false;
                if (key != null) {
                    try { data = decryptSegment(key, iv, mediaSeq + idx, data); }
                    catch (Exception e) { throw new IOException("Could not decrypt stream", e); }
                }
                if (t.cancelled) return false;
                out.write(data);
                bytes += data.length;
                emit(t, "downloading", null, idx + 1, total, bytes, "");
                persistTask(t, "downloading", idx + 1, total, bytes, initInFile);
            }
        } finally { pool.shutdownNow(); }
        return !t.cancelled;
    }

    private void storeAndFinish(Task t, File tmp, String dir, String base, boolean alreadyMp4) throws IOException {
        String stored;
        long size;
        if (alreadyMp4) {
            // fMP4 (or a direct .mp4 file) is already MP4 — no conversion, no
            // MediaMuxer, nothing that can crash natively. Store it as-is.
            stored = storeFile(tmp, dir, base + ".mp4", "video/mp4");
            size = tmp.length();
            tmp.delete();
        } else {
            // MPEG-TS → .mp4 with the pure-Java muxer (the system MediaMuxer
            // is never used: its native MPEG4Writer aborts the whole process
            // on some devices — a C++ crash, uncatchable from Java). Any
            // failure here throws and we keep the raw .ts: the download
            // always succeeds and never crashes.
            emit(t, "remuxing", null, 1, 1, 0, "");
            stored = null;
            size = tmp.length();
            File mp4 = null;
            try { mp4 = remuxToMp4(tmp); }
            catch (Throwable e) {
                if (mp4 != null) mp4.delete();
                mp4 = null;
                // The download still succeeds via the .ts fallback; the reason
                // is logged for debugging (adb logcat -s Elsnime).
                Log.w("Elsnime", "Remux to mp4 failed, keeping .ts: " + e, e);
            }
            if (mp4 != null && mp4.length() > 0) {
                // The remux consumed the .part — drop it so the cache never
                // holds both the TS temp and the mp4 at once (2× an episode).
                tmp.delete();
                try {
                    stored = storeFile(mp4, dir, base + ".mp4", "video/mp4");
                    size = mp4.length();
                } finally {
                    // Even if the MediaStore write fails, the remux temp must
                    // not linger in the cache.
                    mp4.delete();
                }
            } else {
                stored = storeFile(tmp, dir, base + ".ts", "video/mp2t");
                size = tmp.length();
                // The .part temp (a full episode's worth of data) must never
                // outlive a finished download — the cache-growth leak.
                tmp.delete();
            }
        }
        if (t.cancelled) {
            if (stored != null) removeStoredFile(dir, stored);
            emit(t, "cancelled", null, 0, 0, 0, ""); // JS removes the entry on this
            return;
        }
        emit(t, "done", stored, 1, 1, size, "");
    }

    // -storage

    private File publicDir(String dir) {
        return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "Elsnime/" + dir);
    }

    private String storeFile(File src, String dir, String fileName, String mime) throws IOException {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Video.Media.DISPLAY_NAME, fileName);
            v.put(MediaStore.Video.Media.MIME_TYPE, mime);
            v.put(MediaStore.Video.Media.RELATIVE_PATH, RELATIVE_ROOT + dir + "/");
            v.put(MediaStore.Video.Media.IS_PENDING, 1);
            Uri uri = ctx.getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new IOException("Could not create Movies/Elsnime/" + dir + "/" + fileName);
            try {
                try (OutputStream os = ctx.getContentResolver().openOutputStream(uri)) {
                    if (os == null) throw new IOException("Could not open output for " + fileName);
                    copy(src, os);
                }
            } catch (IOException e) {
                // Never leave a dangling IS_PENDING row (a broken 0-byte file).
                try { ctx.getContentResolver().delete(uri, null, null); } catch (Exception ignored) {}
                throw e;
            }
            v.clear();
            v.put(MediaStore.Video.Media.IS_PENDING, 0);
            ctx.getContentResolver().update(uri, v, null, null);
            return fileName;
        }
        File outDir = publicDir(dir);
        if (!outDir.exists() && !outDir.mkdirs()) throw new IOException("Could not create " + outDir.getAbsolutePath());
        File out = new File(outDir, fileName);
        try (FileOutputStream os = new FileOutputStream(out)) { copy(src, os); }
        return fileName;
    }

    /** Delete leftover downloader temp files ('.part' downloads and '.mp4'
     *  remux outputs) from the app cache. Only files older than maxAgeMillis
     *  are touched (pass 0 to remove everything). Returns how many were
     *  deleted. */
    private int sweepTemps(long maxAgeMillis) {
        try {
            File[] files = ctx.getCacheDir().listFiles();
            if (files == null) return 0;
            long cutoff = System.currentTimeMillis() - maxAgeMillis;
            int removed = 0;
            for (File f : files) {
                String n = f.getName();
                if (n.endsWith(".part")) {
                    // A temp claimed by a resumed task must survive the sweep.
                    String uid = n.substring(0, n.length() - 5);
                    boolean resuming = false;
                    for (Task t : tasks.values()) if (t.uid.equals(uid)) { resuming = true; break; }
                    if (resuming) continue;
                }
                if ((n.endsWith(".part") || n.endsWith(".mp4")) && f.isFile() && f.lastModified() < cutoff && f.delete()) removed++;
            }
            return removed;
        } catch (Exception ignored) { return 0; }
    }

    // -resume

    /** Re-enqueue every task whose persisted record can still be completed
     *  (partial .part temp present when it had progress). Records without a
     *  surviving temp are dropped — their data is gone. */
    private void resumeInterrupted() {
        try {
            for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
                String key = e.getKey();
                try {
                    JSONObject rec = new JSONObject((String) e.getValue());
                    int segmentsDone = Math.max(0, rec.optInt("segmentsDone", 0));
                    long bytesDone = Math.max(0, rec.optLong("bytesDone", 0));
                    if (segmentsDone > 0 || bytesDone > 0) {
                        File tmp = new File(ctx.getCacheDir(), rec.optString("uid") + ".part");
                        if (!tmp.exists() || tmp.length() < bytesDone) { clearTaskRecord(key); continue; }
                    }
                    Task t = new Task(
                        rec.optString("uid"), rec.optString("anime_id"), rec.optString("anime_title"),
                        rec.optString("episode"), rec.optString("type", "sub"), rec.optString("quality", "auto"));
                    t.resume = new Resume();
                    t.resume.segmentsDone = segmentsDone;
                    t.resume.bytesDone = bytesDone;
                    t.resume.initDone = rec.optBoolean("initDone", false);
                    if (tasks.putIfAbsent(t.key, t) != null) continue;
                    // Let the frontend's boot reconciliation see the task even
                    // if it finishes before the page loads.
                    emit(t, "queued", null, segmentsDone, 0, bytesDone, "");
                    worker.execute(() -> run(t));
                } catch (Exception ex) { clearTaskRecord(key); }
            }
        } catch (Exception ignored) {}
    }

    /** Write/refresh the durable record for a task. The .part file plus this
     *  record are what a later process resumes from. commit() (not apply()) so
     *  the boundary survives even an immediate process death. */
    private void persistTask(Task t, String state, int segmentsDone, int segmentsTotal, long bytesDone, boolean initDone) {
        if (t.cancelled) return; // a cancelled download must never leave a resumable record
        try {
            JSONObject rec = new JSONObject()
                .put("uid", t.uid)
                .put("anime_id", t.animeId)
                .put("anime_title", t.animeTitle)
                .put("episode", t.episode)
                .put("type", t.type)
                .put("quality", t.quality)
                .put("state", state)
                .put("segmentsDone", segmentsDone)
                .put("segmentsTotal", segmentsTotal)
                .put("bytesDone", bytesDone)
                .put("initDone", initDone);
            prefs.edit().putString(t.key, rec.toString()).commit();
        } catch (Exception ignored) {}
    }

    private void clearTaskRecord(String key) {
        try { prefs.edit().remove(key).commit(); } catch (Exception ignored) {}
    }

    private static void truncate(File f, long size) throws IOException {
        try (FileChannel ch = FileChannel.open(f.toPath(), StandardOpenOption.WRITE)) {
            ch.truncate(size);
        }
    }

    private void removeStoredFile(String dir, String fileName) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                Uri uri = queryUri(RELATIVE_ROOT + dir + "/", fileName);
                if (uri != null) ctx.getContentResolver().delete(uri, null, null);
            } else {
                new File(publicDir(dir), fileName).delete();
            }
        } catch (Exception ignored) {}
    }

    private JSONObject queryFile(String relDir, String name) {
        Uri uri = queryUri(relDir, name);
        if (uri == null) return null;
        try (Cursor c = ctx.getContentResolver().query(uri,
                new String[]{MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String dirName = relDir.startsWith(RELATIVE_ROOT) ? relDir.substring(RELATIVE_ROOT.length()).replace("/", "") : relDir;
                return new JSONObject().put("fileName", c.getString(0)).put("dirName", dirName).put("size", c.getLong(1));
            }
        } catch (Exception ignored) {}
        return null;
    }

    private Uri queryUri(String relDir, String name) {
        try (Cursor c = ctx.getContentResolver().query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.Video.Media._ID},
                MediaStore.Video.Media.RELATIVE_PATH + "=? AND " + MediaStore.Video.Media.DISPLAY_NAME + "=?",
                new String[]{relDir, name}, null)) {
            if (c != null && c.moveToFirst()) {
                return Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, String.valueOf(c.getLong(0)));
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** Opens a downloaded file for streaming, positioned at the given byte
     *  offset (Range requests from the player). Returns null when missing. */
    InputStream openFile(String dir, String fileName, long offset) throws IOException {
        if (Build.VERSION.SDK_INT >= 29) {
            Uri uri = queryUri(RELATIVE_ROOT + dir + "/", fileName);
            if (uri == null) return null;
            final android.content.res.AssetFileDescriptor afd = ctx.getContentResolver().openAssetFileDescriptor(uri, "r");
            if (afd == null) return null;
            FileInputStream in = new FileInputStream(afd.getFileDescriptor());
            if (offset > 0) in.getChannel().position(offset);
            return new java.io.FilterInputStream(in) {
                @Override public void close() throws IOException {
                    try { super.close(); } finally { try { afd.close(); } catch (Exception ignored) {} }
                }
            };
        }
        File f = new File(publicDir(dir), fileName);
        if (!f.exists() || !f.isFile()) return null;
        FileInputStream in = new FileInputStream(f);
        if (offset > 0) in.getChannel().position(offset);
        return in;
    }

    /** Byte length of a downloaded file, or -1 when missing. */
    long fileLength(String dir, String fileName) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                Uri uri = queryUri(RELATIVE_ROOT + dir + "/", fileName);
                if (uri == null) return -1;
                try (android.content.res.AssetFileDescriptor afd = ctx.getContentResolver().openAssetFileDescriptor(uri, "r")) {
                    if (afd != null && afd.getLength() > 0) return afd.getLength();
                }
                JSONObject f = queryFile(RELATIVE_ROOT + dir + "/", fileName);
                return f != null ? f.optLong("size", -1) : -1;
            }
            File f = new File(publicDir(dir), fileName);
            return f.exists() && f.isFile() ? f.length() : -1;
        } catch (Exception e) { return -1; }
    }

    // -remux

    /** Sanity cap for a single sample's buffer. Real TS samples (even 4K
     *  keyframes) are a few MB; a bogus huge size must not balloon into a
     *  multi-GB allocation that OOM-crashes the app. */
    private static final long MAX_SAMPLE_BYTES = 64L * 1024 * 1024;

    /** Concatenated .ts → .mp4 via the media3 {@link Mp4Muxer} (a pure-Java
     *  writer — the system MediaMuxer is never used, because its native
     *  MPEG4Writer aborts the whole process on some devices). Only H.264/HEVC
     *  video + AAC audio are muxed; anything else throws and the caller keeps
     *  the raw .ts, so the download still succeeds. */
    private File remuxToMp4(File ts) throws Exception {
        File out = new File(ctx.getCacheDir(), ts.getName().replace(".part", "") + ".mp4");
        String tsPath = ts.getAbsolutePath();
        MediaExtractor probe = new MediaExtractor();
        MediaFormat videoFmt = null, audioFmt = null;
        int videoTrack = -1, audioTrack = -1;
        try {
            probe.setDataSource(tsPath);
            for (int i = 0; i < probe.getTrackCount(); i++) {
                MediaFormat f = probe.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime == null) continue;
                if (mime.startsWith("video/") && videoTrack < 0) {
                    if (!"video/avc".equals(mime) && !"video/hevc".equals(mime))
                        throw new IOException("Unsupported video codec " + mime);
                    // "csd-0" (KEY_CSD_*) is hidden in the SDK stubs, so it's
                    // referenced by its literal value.
                    if (!f.containsKey("csd-0")) throw new IOException("Video stream has no codec config");
                    videoTrack = i;
                    videoFmt = f;
                } else if (mime.startsWith("audio/") && audioTrack < 0) {
                    if (!"audio/mp4a-latm".equals(mime)) throw new IOException("Unsupported audio codec " + mime);
                    audioTrack = i;
                    audioFmt = f;
                }
            }
        } finally { probe.release(); }
        if (videoTrack < 0) throw new IOException("No muxable video track in stream");

        try (Muxer muxer = new Mp4Muxer.Builder(
                new FileOutputStreamSeekableMuxerOutput(new FileOutputStream(out))).build()) {
            // csd-0 is parsed into NAL units (either AVCC length-prefixed or
            // Annex-B start codes — both occur in the wild); the SPS/PPS (and
            // VPS for HEVC) are pulled from them by NAL type and handed to the
            // muxer as the track's initialization data.
            List<byte[]> nals = parseNals(getCsd(videoFmt, 0));
            if (nals.isEmpty()) throw new IOException("Video stream has no codec config");
            int width, height;
            try {
                width = videoFmt.getInteger(MediaFormat.KEY_WIDTH);
                height = videoFmt.getInteger(MediaFormat.KEY_HEIGHT);
            } catch (Exception e) { throw new IOException("Video stream has no dimensions"); }
            List<byte[]> videoCsd = new ArrayList<>();
            if ("video/hevc".equals(videoFmt.getString(MediaFormat.KEY_MIME))) {
                byte[] vps = null, sps = null, pps = null;
                for (byte[] nal : nals) {
                    if (nal.length < 2) continue;
                    int type = (nal[0] & 0x7E) >> 1;
                    if (type == 32 && vps == null) vps = nal;
                    else if (type == 33 && sps == null) sps = nal;
                    else if (type == 34 && pps == null) pps = nal;
                }
                if (vps == null || sps == null || pps == null)
                    throw new IOException("HEVC stream missing VPS/SPS/PPS");
                // media3 expects H.265 csd-0 to hold all three NALs in one
                // Annex-B blob, VPS/SPS/PPS in that order.
                ByteArrayOutputStream hevcCsd = new ByteArrayOutputStream();
                hevcCsd.write(annexB(vps));
                hevcCsd.write(annexB(sps));
                hevcCsd.write(annexB(pps));
                videoCsd.add(hevcCsd.toByteArray());
            } else {
                byte[] sps = null, pps = null;
                for (byte[] nal : nals) {
                    if (nal.length < 1) continue;
                    int type = nal[0] & 0x1F;
                    if (type == 7 && sps == null) sps = nal;
                    else if (type == 8 && pps == null) pps = nal;
                }
                if (pps == null) {
                    // Some extractors put the PPS in csd-1 instead of csd-0.
                    for (byte[] nal : parseNals(getCsd(videoFmt, 1))) {
                        if (nal.length >= 1 && (nal[0] & 0x1F) == 8) { pps = nal; break; }
                    }
                }
                if (sps == null || sps.length < 4 || pps == null)
                    throw new IOException("Video stream has invalid codec config");
                // media3 expects csd-0 = SPS and csd-1 = PPS, each an
                // Annex-B-wrapped NAL.
                videoCsd.add(annexB(sps));
                videoCsd.add(annexB(pps));
            }
            Format videoFormat = new Format.Builder()
                    .setSampleMimeType(videoFmt.getString(MediaFormat.KEY_MIME))
                    .setWidth(width)
                    .setHeight(height)
                    .setInitializationData(videoCsd)
                    .build();
            int videoTrackId = muxer.addTrack(videoFormat);
            int audioTrackId = -1;
            if (audioTrack >= 0) {
                byte[] asc = toByteArray(getCsd(audioFmt, 0));
                int aRate = 0, aCh = 0;
                try { aRate = audioFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE); } catch (Exception ignored) {}
                try { aCh = audioFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT); } catch (Exception ignored) {}
                if (asc == null || asc.length == 0) {
                    // ADTS AAC in TS often carries no csd; the first sample's
                    // ADTS header is ground truth for rate/channels (Android's
                    // extractor can report a wrong/absent rate). Fall back to
                    // the format's values when it can't be parsed.
                    long[] adts = peekAdtsRateChannels(tsPath, audioTrack);
                    if (adts != null) { aRate = (int) adts[0]; aCh = (int) adts[1]; }
                    if (aRate <= 0 || aCh <= 0)
                        throw new IOException("Audio stream has no codec config");
                    asc = buildAudioSpecificConfig(aRate, aCh);
                }
                Format audioFormat = new Format.Builder()
                        .setSampleMimeType("audio/mp4a-latm")
                        .setSampleRate(aRate)
                        .setChannelCount(aCh)
                        .setInitializationData(Collections.singletonList(asc))
                        .build();
                audioTrackId = muxer.addTrack(audioFormat);
            }
            boolean wroteVideo = writeTrackToMuxer(tsPath, videoTrack, muxer, videoTrackId, true) > 0;
            boolean wroteAudio = audioTrackId < 0 || writeTrackToMuxer(tsPath, audioTrack, muxer, audioTrackId, false) > 0;
            if (!wroteVideo || !wroteAudio || out.length() <= 0) throw new IOException("Stream could not be muxed to mp4");
        } catch (Throwable t) {
            // Any failure — including an Error mid-write — must not leave the
            // partial mp4 temp behind in the cache.
            out.delete();
            throw t;
        }
        return out;
    }

    private static ByteBuffer getCsd(MediaFormat f, int i) {
        try {
            ByteBuffer b = f.getByteBuffer("csd-" + i).duplicate();
            b.position(0);
            return b;
        } catch (Exception e) { return null; }
    }

    private static byte[] toByteArray(ByteBuffer b) {
        if (b == null || b.remaining() == 0) return null;
        byte[] out = new byte[b.remaining()];
        b.get(out);
        return out;
    }

    /** Splits a csd buffer into NAL units, accepting either the AVCC format
     *  (4-byte big-endian length prefixes) or Annex-B start codes. The NAL
     *  header byte(s) are kept. */
    private static List<byte[]> parseNals(ByteBuffer b) {
        List<byte[]> out = new ArrayList<>();
        if (b == null || b.remaining() == 0) return out;
        byte[] raw = new byte[b.remaining()];
        b.get(raw);
        boolean annexB = (raw.length >= 3 && raw[0] == 0 && raw[1] == 0 && raw[2] == 1)
            || (raw.length >= 4 && raw[0] == 0 && raw[1] == 0 && raw[2] == 0 && raw[3] == 1);
        if (annexB) {
            int p = 0;
            while (p < raw.length) {
                int sc = annexBStartCode(raw, p);
                if (sc == 0) { p++; continue; }
                p += sc;
                int start = p;
                while (p < raw.length && annexBStartCode(raw, p) == 0) p++;
                if (p > start) out.add(java.util.Arrays.copyOfRange(raw, start, p));
            }
        } else {
            int p = 0;
            while (p + 4 <= raw.length) {
                int len = ((raw[p] & 0xFF) << 24) | ((raw[p + 1] & 0xFF) << 16)
                    | ((raw[p + 2] & 0xFF) << 8) | (raw[p + 3] & 0xFF);
                if (len <= 0 || len > raw.length - p - 4) break;
                out.add(java.util.Arrays.copyOfRange(raw, p + 4, p + 4 + len));
                p += 4 + len;
            }
        }
        return out;
    }

    /** Wraps a raw NAL unit in a 4-byte Annex-B start code — the format the
     *  media3 muxer expects for avcC/hvcC initialization data. */
    private static byte[] annexB(byte[] nal) {
        byte[] out = new byte[nal.length + 4];
        out[0] = 0; out[1] = 0; out[2] = 0; out[3] = 1;
        System.arraycopy(nal, 0, out, 4, nal.length);
        return out;
    }

    /** Length of an Annex-B start code at p (4 for 00 00 00 01, 3 for 00 00 01). */
    private static int annexBStartCode(byte[] raw, int p) {
        if (p + 3 < raw.length && raw[p] == 0 && raw[p + 1] == 0 && raw[p + 2] == 0 && raw[p + 3] == 1) return 4;
        if (p + 2 < raw.length && raw[p] == 0 && raw[p + 1] == 0 && raw[p + 2] == 1) return 3;
        return 0;
    }

    /** 2-byte AudioSpecificConfig for AAC-LC (ISO/IEC 14496-3), as required by
     *  the mp4a esds box when the source carried no codec config (ADTS). */
    private static byte[] buildAudioSpecificConfig(int sampleRate, int channelCount) throws IOException {
        int srIdx;
        switch (sampleRate) {
            case 96000: srIdx = 0; break;
            case 88200: srIdx = 1; break;
            case 64000: srIdx = 2; break;
            case 48000: srIdx = 3; break;
            case 44100: srIdx = 4; break;
            case 32000: srIdx = 5; break;
            case 24000: srIdx = 6; break;
            case 22050: srIdx = 7; break;
            case 16000: srIdx = 8; break;
            case 12000: srIdx = 9; break;
            case 11025: srIdx = 10; break;
            case 8000: srIdx = 11; break;
            case 7350: srIdx = 12; break;
            default: throw new IOException("Unsupported AAC sample rate " + sampleRate);
        }
        // channelConfiguration: 1-7 are mono..7.1(8ch); 8+ can't be signaled.
        int chanConfig = channelCount == 8 ? 7 : channelCount;
        if (chanConfig < 1 || chanConfig > 7) throw new IOException("Unsupported AAC channel count " + channelCount);
        byte[] asc = new byte[2];
        // bits: [objectType(5)=AAC-LC][sampleRateIndex(4)][channelConfig(4)][frameLength(3)=0]
        asc[0] = (byte) (((2 << 3) | (srIdx >> 1)) & 0xFF);
        asc[1] = (byte) ((((srIdx & 1) << 7) | (chanConfig << 3)) & 0xFF);
        return asc;
    }

    /** Reads the first audio sample's ADTS sync header and returns
     *  {sampleRate, channels} — the true values when the format carries no
     *  csd (Android's TS extractor may report a wrong or absent rate).
     *  Returns null when the sample isn't an ADTS frame. */
    private static long[] peekAdtsRateChannels(String tsPath, int track) {
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(tsPath);
            ex.selectTrack(track);
            long need = ex.getSampleSize();
            if (need <= 0 || need > 4096) return null;
            ByteBuffer buf = ByteBuffer.allocate((int) need);
            int n = ex.readSampleData(buf, 0);
            if (n < 7) return null;
            byte[] h = new byte[7];
            ByteBuffer d = buf.duplicate();
            d.position(0);
            d.limit(n);
            d.get(h);
            // syncword 0xFFF, MPEG layer 00; protection bit may be either.
            if ((h[0] & 0xFF) != 0xFF || (h[1] & 0xF6) != 0xF0) return null;
            int sfIdx = (h[2] >> 2) & 0x0F;
            int chan = ((h[2] & 0x01) << 2) | ((h[3] >> 6) & 0x03);
            if (sfIdx > 12 || chan < 1) return null;
            int[] rates = {96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350};
            return new long[]{rates[sfIdx], chan};
        } catch (Exception e) { return null; }
        finally { ex.release(); }
    }

    /** Streams one track's samples into the media3 muxer; returns the sample
     *  count so the caller can reject streams that yield nothing. */
    private long writeTrackToMuxer(String tsPath, int track, Muxer muxer, int trackId, boolean isVideo) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(tsPath);
            ex.selectTrack(track);
            ByteBuffer buf = ByteBuffer.allocate(4 * 1024 * 1024);
            long count = 0;
            while (true) {
                // getSampleSize() is -1 when the stream is exhausted.
                long need = ex.getSampleSize();
                if (need <= 0) break;
                // Cap the buffer: growing it to fit a bogus size would OOM.
                if (need > MAX_SAMPLE_BYTES) throw new IOException("Sample larger than " + MAX_SAMPLE_BYTES + " bytes");
                if (need > buf.capacity()) buf = ByteBuffer.allocate((int) need);
                int n = ex.readSampleData(buf, 0);
                if (n <= 0) break;
                long pts = ex.getSampleTime();
                if (pts < 0) { ex.advance(); continue; }
                boolean sync = (ex.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0;
                byte[] data = new byte[n];
                ByteBuffer d = buf.duplicate();
                d.position(0);
                d.limit(n);
                d.get(data);
                // The muxer derives stts/ctts and all durations from the
                // sample timestamps itself; only the keyframe flag matters.
                int flags = isVideo && sync ? C.BUFFER_FLAG_KEY_FRAME : 0;
                muxer.writeSampleData(trackId, ByteBuffer.wrap(data), new BufferInfo(pts, n, flags));
                count++;
                ex.advance();
            }
            return count;
        } finally { ex.release(); }
    }

    // -http

    private String fetchText(String url, String referer) throws IOException {
        return new String(fetchBytes(url, referer), StandardCharsets.UTF_8);
    }

    private byte[] fetchSegment(String url, String referer) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try { return fetchBytes(url, referer); }
            catch (IOException e) {
                last = e;
                try { Thread.sleep(1000L * (attempt + 1)); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw last; }
            }
        }
        throw last;
    }

    private byte[] fetchBytes(String url, String referer) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept", "*/*");
        if (referer != null && !referer.isEmpty()) c.setRequestProperty("Referer", referer);
        try {
            int code = c.getResponseCode();
            if (code >= 400) throw new IOException("HTTP " + code);
            // Pre-size from Content-Length when the server provides it, so a
            // big segment doesn't force repeated buffer doubling (copies).
            int clen = c.getContentLength();
            if (clen > MAX_SEGMENT_BYTES) throw new IOException("Response larger than " + MAX_SEGMENT_BYTES + " bytes");
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream(clen > 0 ? clen : 65536)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (out.size() + n > MAX_SEGMENT_BYTES) throw new IOException("Response larger than " + MAX_SEGMENT_BYTES + " bytes");
                    out.write(buf, 0, n);
                }
                return out.toByteArray();
            }
        } finally { c.disconnect(); }
    }

    // -misc

    /** AES-128-CBC per HLS spec: IV from the playlist attribute (iv != null) or
     *  the media sequence number; the leading 16-byte encrypted-IV block is
     *  discarded from the plaintext. */
    private static byte[] decryptSegment(byte[] key, byte[] iv, long seq, byte[] data) throws Exception {
        if (iv == null) {
            iv = new byte[16];
            for (int i = 0; i < 8; i++) iv[8 + i] = (byte) (seq >>> (8 * (7 - i)));
        }
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        byte[] out = cipher.doFinal(data);
        return java.util.Arrays.copyOfRange(out, Math.min(16, out.length), out.length);
    }

    private void emit(Task t, String state, String fileName, int segDone, int segTotal, long bytes, String error) {
        try {
            JSONObject ev = new JSONObject()
                .put("uid", t.uid)
                .put("anime_id", t.animeId)
                .put("anime_title", t.animeTitle)
                .put("episode", t.episode)
                .put("state", state)
                .put("fileName", fileName == null ? "" : fileName)
                .put("segmentsDone", segDone)
                .put("segmentsTotal", segTotal)
                .put("bytesDone", bytes)
                .put("error", error == null ? "" : error)
                .put("type", t.type)
                .put("quality", t.quality);
            if (segTotal > 0) ev.put("progress", Math.min(1.0, segDone / (double) segTotal));
            lastEvent.put(t.key, ev);
            for (Listener l : listeners) {
                // One bad listener (e.g. a dead WebView) must not drop events
                // for the others (the service's progress notification).
                try { l.onEvent(ev); } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }

    static String sanitizeTitle(String t) {
        String s = t == null ? "" : t.replaceAll("[\\\\/:*?\"<>|]", " ").replaceAll("\\s+", " ").trim();
        if (s.length() > 80) s = s.substring(0, 80).trim();
        return s.isEmpty() ? "Anime" : s;
    }

    private static int parseHeight(String quality) {
        if (quality == null || quality.trim().isEmpty() || "auto".equalsIgnoreCase(quality)) return 0;
        try { return Math.max(0, Integer.parseInt(quality.trim())); } catch (Exception e) { return 0; }
    }

    private static String absoluteUrl(String base, String value) {
        try { return new URL(new URL(base), value).toString(); } catch (Exception e) { return value; }
    }

    /** True when the file starts with an ISO-BMFF 'ftyp' box — i.e. it's
     *  already an MP4 and must not go through the TS remux. */
    private static boolean looksLikeMp4(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] h = new byte[12];
            int n = in.read(h);
            return n >= 12 && h[4] == 'f' && h[5] == 't' && h[6] == 'y' && h[7] == 'p';
        } catch (Exception e) { return false; }
    }

    private static void copy(File src, OutputStream os) throws IOException {
        try (FileInputStream in = new FileInputStream(src)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        }
    }
}
