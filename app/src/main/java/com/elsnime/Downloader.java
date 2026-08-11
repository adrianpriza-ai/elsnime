package com.elsnime;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Downloads an anime episode (HLS, from {@link AniDbScraper#stream}) into the
 * public Movies/Elsnime/&lt;anime&gt;/ folder as an MP4 — the MPEG-TS segments
 * are fetched and concatenated, then remuxed to MP4 via MediaMuxer, falling
 * back to a raw .ts file when a stream can't be muxed (so the download never
 * fails purely because of the container).
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
    private final Listener listener;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Map<String, Task> tasks = new ConcurrentHashMap<>(); // "animeId|episode" -> Task

    Downloader(Context c, AniDbScraper s, Listener l) {
        ctx = c.getApplicationContext();
        scraper = s;
        listener = l;
    }

    private static final class Task {
        final String uid, animeId, animeTitle, episode, type, quality, key;
        volatile boolean cancelled;
        Task(String uid, String animeId, String animeTitle, String episode, String type, String quality) {
            this.uid = uid; this.animeId = animeId; this.animeTitle = animeTitle;
            this.episode = episode; this.type = type; this.quality = quality;
            this.key = animeId + "|" + episode;
        }
    }

    /** Enqueue a download (no-op if one for this anime+episode is already active). */
    void start(JSONObject req) {
        Task t = new Task(
            req.optString("uid"), req.optString("anime_id"), req.optString("anime_title"),
            req.optString("episode"), req.optString("type", "sub"), req.optString("quality", "auto"));
        if (tasks.putIfAbsent(t.key, t) != null) return;
        worker.execute(() -> run(t));
    }

    boolean isActive(String key) { return tasks.containsKey(key); }

    void cancel(String key) {
        Task t = tasks.get(key);
        if (t != null) t.cancelled = true;
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

    // ---------------------------------------------------------------- run

    private void run(Task t) {
        File tmp = new File(ctx.getCacheDir(), t.uid + ".part");
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
            if (manifest.contains("#EXT-X-MAP")) {
                emit(t, "error", null, 0, 0, 0, "This stream uses fMP4 segments and can't be downloaded");
                return;
            }

            String dir = sanitizeTitle(t.animeTitle);
            String base = dir + " - Episode " + t.episode;

            if (!manifest.contains("#EXTM3U")) {
                // Not a playlist: a single-file stream — just fetch it.
                boolean ok = downloadSegments(t, Collections.singletonList(url), null, null, 0, referer, tmp);
                if (!ok) { tmp.delete(); emit(t, "cancelled", null, 0, 0, 0, ""); return; }
                storeAndFinish(t, tmp, dir, base);
                return;
            }

            // Parse the media playlist: segment URIs + optional AES-128 key.
            List<String> segments = new ArrayList<>();
            String keyUrl = null;
            byte[] iv = null;
            long mediaSeq = 0;
            for (String line : manifest.split("\n")) {
                line = line.trim();
                if (line.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                    try { mediaSeq = Long.parseLong(line.substring(line.indexOf(':') + 1).trim()); } catch (Exception ignored) {}
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

            boolean ok = downloadSegments(t, segments, keyUrl, iv, mediaSeq, referer, tmp);
            if (!ok) { tmp.delete(); emit(t, "cancelled", null, 0, 0, 0, ""); return; }
            storeAndFinish(t, tmp, dir, base);
        } catch (Exception e) {
            tmp.delete();
            if (!t.cancelled) {
                String msg = e.getMessage();
                emit(t, "error", null, 0, 0, 0, (msg == null || msg.isEmpty()) ? "Download failed" : msg);
            }
        } finally {
            tasks.remove(t.key);
        }
    }

    private boolean downloadSegments(Task t, List<String> segments, String keyUrl, byte[] iv, long mediaSeq, String referer, File tmp) throws IOException {
        byte[] key = null;
        if (keyUrl != null && !keyUrl.isEmpty()) {
            key = fetchBytes(keyUrl, referer);
            if (key == null || key.length != 16) throw new IOException("Could not load the stream's decryption key");
        }
        int total = segments.size();
        long bytes = 0;
        emit(t, "downloading", null, 0, total, 0, "");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            for (int i = 0; i < total; i++) {
                if (t.cancelled) return false;
                byte[] data = fetchSegment(segments.get(i), referer);
                if (t.cancelled) return false;
                if (key != null) {
                    try { data = decryptSegment(key, iv, mediaSeq + i, data); }
                    catch (Exception e) { throw new IOException("Could not decrypt stream", e); }
                }
                if (t.cancelled) return false;
                out.write(data);
                bytes += data.length;
                emit(t, "downloading", null, i + 1, total, bytes, "");
            }
        }
        return !t.cancelled;
    }

    private void storeAndFinish(Task t, File tmp, String dir, String base) throws IOException {
        emit(t, "remuxing", null, 1, 1, 0, "");
        String stored = null;
        long size = tmp.length();
        File mp4 = new File(ctx.getCacheDir(), tmp.getName().replace(".part", "") + ".mp4");
        try { mp4 = remuxToMp4(tmp); } catch (Exception ignored) { mp4.delete(); mp4 = null; }
        if (mp4 != null && mp4.length() > 0) {
            stored = storeFile(mp4, dir, base + ".mp4", "video/mp4");
            size = mp4.length();
            mp4.delete();
        } else {
            stored = storeFile(tmp, dir, base + ".ts", "video/mp2t");
        }
        if (t.cancelled) {
            if (stored != null) removeStoredFile(dir, stored);
            emit(t, "cancelled", null, 0, 0, 0, ""); // JS removes the entry on this
            return;
        }
        emit(t, "done", stored, 1, 1, size, "");
    }

    // ---------------------------------------------------------------- storage

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

    // ---------------------------------------------------------------- remux

    /** Concatenated .ts → .mp4 via MediaExtractor/MediaMuxer. Throws when the
     *  stream can't be muxed (caller falls back to keeping the raw .ts). */
    private File remuxToMp4(File ts) throws Exception {
        File out = new File(ctx.getCacheDir(), ts.getName().replace(".part", "") + ".mp4");
        int videoTrack = -1, audioTrack = -1;
        String tsPath = ts.getAbsolutePath();
        MediaExtractor probe = new MediaExtractor();
        try {
            probe.setDataSource(tsPath);
            for (int i = 0; i < probe.getTrackCount(); i++) {
                MediaFormat f = probe.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime == null) continue;
                if (mime.startsWith("video/") && videoTrack < 0) videoTrack = i;
                else if (mime.startsWith("audio/") && audioTrack < 0) audioTrack = i;
            }
        } finally { probe.release(); }
        if (videoTrack < 0) throw new IOException("No video track in stream");

        MediaMuxer muxer = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        int mv = muxer.addTrack(trackFormat(tsPath, videoTrack));
        int ma = audioTrack >= 0 ? muxer.addTrack(trackFormat(tsPath, audioTrack)) : -1;
        muxer.start();
        try {
            writeTrack(tsPath, videoTrack, mv, muxer);
            if (audioTrack >= 0) writeTrack(tsPath, audioTrack, ma, muxer);
        } finally {
            try { muxer.stop(); } catch (Exception ignored) {}
            muxer.release();
        }
        return out;
    }

    private MediaFormat trackFormat(String tsPath, int track) throws IOException {
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(tsPath);
            return ex.getTrackFormat(track);
        } finally { ex.release(); }
    }

    private void writeTrack(String tsPath, int track, int outTrack, MediaMuxer muxer) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(tsPath);
            ex.selectTrack(track);
            ByteBuffer buf = ByteBuffer.allocate(4 * 1024 * 1024);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long lastPts = Long.MIN_VALUE, offset = 0;
            while (true) {
                // Grow the buffer when a sample (e.g. a large keyframe) is bigger
                // than it — readSampleData would otherwise truncate the sample.
                long need = ex.getSampleSize();
                if (need > buf.capacity()) buf = ByteBuffer.allocate((int) Math.min(need, Integer.MAX_VALUE - 8));
                int n = ex.readSampleData(buf, 0);
                if (n < 0) break;
                long pts = ex.getSampleTime();
                if (pts < 0) { ex.advance(); continue; }
                // HLS segments restart their PTS near 0; bump a running offset at
                // each boundary so MediaMuxer sees monotonically increasing times.
                if (lastPts != Long.MIN_VALUE && pts < lastPts) offset += (lastPts - pts) + 40000;
                pts += offset;
                lastPts = pts;
                int flags = (ex.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                    ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                info.set(0, n, pts, flags);
                muxer.writeSampleData(outTrack, buf, info);
                ex.advance();
            }
        } finally { ex.release(); }
    }

    // ---------------------------------------------------------------- http

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
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return out.toByteArray();
            }
        } finally { c.disconnect(); }
    }

    // ---------------------------------------------------------------- misc

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
                .put("error", error == null ? "" : error);
            if (segTotal > 0) ev.put("progress", Math.min(1.0, segDone / (double) segTotal));
            listener.onEvent(ev);
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

    private static void copy(File src, OutputStream os) throws IOException {
        try (FileInputStream in = new FileInputStream(src)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        }
    }
}
