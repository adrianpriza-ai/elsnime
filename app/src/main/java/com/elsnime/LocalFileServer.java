package com.elsnime;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.LongFunction;

/**
 * Tiny loopback-only HTTP server ({@code 127.0.0.1}) that streams downloaded
 * files to the in-app player. The WebView page is a file:// origin, which
 * cannot fetch content:// URIs (WebView blocks them), so downloads are served
 * over plain http to the loopback interface instead. Range requests are
 * honored so {@code <video>}/hls.js can seek; for MPEG-TS files the same
 * entry also serves a generated single-segment VOD playlist ({@code .m3u8})
 * that hls.js plays. Entries are small (an opener lambda), so old ones just
 * linger harmlessly for the app's lifetime.
 */
final class LocalFileServer {

    private static final String HOST = "127.0.0.1";
    private static final int CHUNK = 65536;

    private static final class Entry {
        final long length;
        final String mime;
        final LongFunction<InputStream> opener;
        Entry(long length, String mime, LongFunction<InputStream> opener) {
            this.length = length;
            this.mime = mime;
            this.opener = opener;
        }
    }

    private static final class HlsProxy {
        final String token;
        final String referer;
        HlsProxy(String token, String referer) { this.token = token; this.referer = referer; }
    }

    private static volatile LocalFileServer instance;

    static LocalFileServer get() {
        if (instance == null) {
            synchronized (LocalFileServer.class) {
                if (instance == null) instance = new LocalFileServer();
            }
        }
        return instance;
    }

    private final ServerSocket server;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final Map<String, HlsProxy> hlsProxies = new ConcurrentHashMap<>();
    private final int port;

    private LocalFileServer() {
        try {
            server = new ServerSocket(0, 16, InetAddress.getByName(HOST));
            port = server.getLocalPort();
        } catch (IOException e) {
            throw new RuntimeException("Could not start the local file server", e);
        }
        Thread t = new Thread(this::acceptLoop, "local-file-server");
        t.setDaemon(true);
        t.start();
    }

    /** Registers a file; returns the base URL ({@code http://127.0.0.1:PORT/token}). */
    String register(String token, long length, String mime, LongFunction<InputStream> opener) {
        entries.put(token, new Entry(length, mime, opener));
        return "http://" + HOST + ":" + port + "/" + token;
    }

    /** Routes an HLS master and all nested playlists/segments through loopback,
     *  adding the provider's required headers on native HTTP requests. */
    String registerHlsProxy(String master, String referer) {
        String token = "hls" + UUID.randomUUID().toString().replace("-", "");
        HlsProxy proxy = new HlsProxy(token, referer);
        hlsProxies.put(token, proxy);
        return proxyUrl(token, master, "master.m3u8");
    }

    void unregister(String token) { entries.remove(token); }

    private void acceptLoop() {
        while (true) {
            Socket s;
            try {
                s = server.accept();
            } catch (IOException e) {
                return; // server closed
            }
            pool.execute(() -> handle(s));
        }
    }

    private void handle(Socket s) {
        try (Socket socket = s;
             InputStream in = socket.getInputStream();
             OutputStream out = socket.getOutputStream()) {
            String req = readLine(in);
            if (req == null) return;
            String[] parts = req.split(" ");
            String method = parts.length > 0 ? parts[0] : "GET";
            String path = parts.length > 1 ? parts[1] : "/";
            String range = null;
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                if (line.regionMatches(true, 0, "Range:", 0, 6)) range = line.substring(6).trim();
            }

            String p = path.startsWith("/") ? path.substring(1) : path;
            if (p.startsWith("hls/")) {
                int slash = p.indexOf('/', 4);
                if (slash < 0) {
                    respond(out, "404 Not Found", "text/plain", "Not found".getBytes(StandardCharsets.UTF_8), method);
                    return;
                }
                String token = p.substring(4, slash);
                HlsProxy proxy = hlsProxies.get(token);
                String target = queryParameter(path, "u");
                if (proxy == null || target.isEmpty()) {
                    proxyRespond(out, "404 Not Found", "text/plain", "Not found".getBytes(StandardCharsets.UTF_8), method);
                    return;
                }
                proxyRequest(out, method, target, proxy, range);
                return;
            }
            boolean playlist = p.endsWith(".m3u8");
            String token = playlist ? p.substring(0, p.length() - 5) : p;
            Entry e = entries.get(token);
            if (e == null) {
                respond(out, "404 Not Found", "text/plain", "Not found".getBytes(StandardCharsets.UTF_8), method);
                return;
            }
            if (playlist) {
                respond(out, "200 OK", "application/vnd.apple.mpegurl", playlist(token).getBytes(StandardCharsets.UTF_8), method);
                return;
            }

            long start = 0, end = e.length - 1;
            boolean partial = false;
            if (range != null && range.startsWith("bytes=")) {
                String r = range.substring(6);
                int dash = r.indexOf('-');
                try {
                    long s0 = Long.parseLong(r.substring(0, dash));
                    if (s0 >= 0 && s0 < e.length) { start = s0; partial = true; }
                    if (dash + 1 < r.length() && !r.substring(dash + 1).isEmpty()) {
                        long e0 = Long.parseLong(r.substring(dash + 1));
                        if (e0 < e.length) end = e0;
                    }
                } catch (Exception ignored) {}
            }
            long len = end - start + 1;

            StringBuilder h = new StringBuilder();
            h.append("HTTP/1.1 ").append(partial ? "206 Partial Content" : "200 OK").append("\r\n");
            h.append("Content-Type: ").append(e.mime).append("\r\n");
            h.append("Accept-Ranges: bytes\r\n");
            h.append("Content-Length: ").append(len).append("\r\n");
            if (partial) {
                h.append("Content-Range: bytes ").append(start).append('-').append(end).append('/').append(e.length).append("\r\n");
            }
            h.append("Connection: close\r\n\r\n");
            out.write(h.toString().getBytes(StandardCharsets.ISO_8859_1));
            if (method.equals("HEAD")) return;

            try (InputStream is = e.opener.apply(start)) {
                if (is == null) return;
                byte[] buf = new byte[CHUNK];
                long remaining = len;
                int n;
                while (remaining > 0 && (n = is.read(buf, 0, (int) Math.min(buf.length, remaining))) > 0) {
                    out.write(buf, 0, n);
                    remaining -= n;
                }
            }
        } catch (Exception ignored) {}
    }

    private void proxyRequest(OutputStream out, String method, String target, HlsProxy proxy, String range) throws IOException {
        if ("OPTIONS".equalsIgnoreCase(method)) {
            proxyRespond(out, "204 No Content", "text/plain", new byte[0], method);
            return;
        }
        HttpURLConnection c = null;
        try {
            URL parsed = new URL(target);
            if (!"http".equalsIgnoreCase(parsed.getProtocol()) && !"https".equalsIgnoreCase(parsed.getProtocol())) {
                proxyRespond(out, "400 Bad Request", "text/plain", "Unsupported protocol".getBytes(StandardCharsets.UTF_8), method);
                return;
            }
            c = (HttpURLConnection) parsed.openConnection();
            c.setRequestMethod(method);
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", AniDbScraper.UA);
            c.setRequestProperty("Accept", "*/*");
            c.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
            if (proxy.referer != null && !proxy.referer.isEmpty()) c.setRequestProperty("Referer", proxy.referer);
            if (range != null && !range.isEmpty()) c.setRequestProperty("Range", range);

            int code = c.getResponseCode();
            InputStream upstream = code >= 400 ? c.getErrorStream() : c.getInputStream();
            boolean playlist = parsed.getPath().toLowerCase(java.util.Locale.ROOT).endsWith(".m3u8")
                || (c.getContentType() != null && c.getContentType().toLowerCase(java.util.Locale.ROOT).contains("mpegurl"));
            if (upstream == null) {
                proxyRespond(out, status(code), "text/plain", new byte[0], method);
                return;
            }
            try (InputStream body = upstream) {
                if (playlist && code >= 200 && code < 300) {
                    byte[] data = readAll(body);
                    String text = new String(data, StandardCharsets.UTF_8);
                    String rewritten = rewritePlaylist(text, c.getURL().toString(), proxy);
                    proxyRespond(out, status(code), "application/vnd.apple.mpegurl", rewritten.getBytes(StandardCharsets.UTF_8), method);
                } else {
                    proxyStream(out, c, body, code, method);
                }
            }
        } catch (Exception e) {
            proxyRespond(out, "502 Bad Gateway", "text/plain", "Upstream stream request failed".getBytes(StandardCharsets.UTF_8), method);
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private String rewritePlaylist(String body, String base, HlsProxy proxy) {
        StringBuilder out = new StringBuilder(body.length() + 128);
        Pattern uriAttribute = Pattern.compile("URI=\\\"([^\\\"]+)\\\"");
        String[] lines = body.split("\\r?\\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.startsWith("#")) {
                Matcher matcher = uriAttribute.matcher(line);
                StringBuffer rewritten = new StringBuffer();
                while (matcher.find()) {
                    String local = localResource(base, matcher.group(1), proxy);
                    if (local != null) matcher.appendReplacement(rewritten, Matcher.quoteReplacement("URI=\"" + local + "\""));
                }
                matcher.appendTail(rewritten);
                out.append(rewritten);
            } else if (!line.trim().isEmpty()) {
                String trimmed = line.trim();
                String local = localResource(base, trimmed, proxy);
                out.append(local == null ? line : local);
            }
            if (i + 1 < lines.length) out.append('\n');
        }
        return out.toString();
    }

    private String localResource(String base, String ref, HlsProxy proxy) {
        try {
            URL resolved = new URL(new URL(base), ref);
            String protocol = resolved.getProtocol();
            if (!"http".equalsIgnoreCase(protocol) && !"https".equalsIgnoreCase(protocol)) return null;
            String path = resolved.getPath().toLowerCase(java.util.Locale.ROOT);
            String name = path.endsWith(".m3u8") ? "playlist.m3u8" : "resource";
            return proxyUrl(proxyToken(proxy), resolved.toString(), name);
        } catch (Exception e) {
            return null;
        }
    }

    private String proxyToken(HlsProxy proxy) {
        return proxy.token;
    }

    private String proxyUrl(String token, String target, String name) {
        try {
            return "http://" + HOST + ":" + port + "/hls/" + token + "/" + name + "?u=" + URLEncoder.encode(target, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private static String queryParameter(String path, String name) {
        int q = path.indexOf('?');
        if (q < 0) return "";
        for (String pair : path.substring(q + 1).split("&")) {
            int equals = pair.indexOf('=');
            if (equals < 0 || !name.equals(pair.substring(0, equals))) continue;
            try { return URLDecoder.decode(pair.substring(equals + 1), "UTF-8"); }
            catch (Exception ignored) { return ""; }
        }
        return "";
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[CHUNK];
        int n;
        while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
        return out.toByteArray();
    }

    private static void proxyStream(OutputStream out, HttpURLConnection upstream, InputStream body, int code, String method) throws IOException {
        long length = upstream.getContentLengthLong();
        String type = upstream.getContentType();
        StringBuilder h = proxyHeaders(status(code), type == null ? "application/octet-stream" : type, length, method);
        String contentRange = upstream.getHeaderField("Content-Range");
        String acceptRanges = upstream.getHeaderField("Accept-Ranges");
        if (contentRange != null) h.insert(h.length() - 2, "Content-Range: " + contentRange + "\r\n");
        if (acceptRanges != null) h.insert(h.length() - 2, "Accept-Ranges: " + acceptRanges + "\r\n");
        out.write(h.toString().getBytes(StandardCharsets.ISO_8859_1));
        if ("HEAD".equalsIgnoreCase(method)) return;
        byte[] buffer = new byte[CHUNK];
        int n;
        if (length >= 0) {
            long remaining = length;
            while (remaining > 0 && (n = body.read(buffer, 0, (int) Math.min(buffer.length, remaining))) != -1) {
                out.write(buffer, 0, n);
                remaining -= n;
            }
        } else {
            while ((n = body.read(buffer)) != -1) {
                out.write(Integer.toHexString(n).getBytes(StandardCharsets.US_ASCII));
                out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
                out.write(buffer, 0, n);
                out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
            }
            out.write("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        }
    }

    private static StringBuilder proxyHeaders(String status, String mime, long length, String method) {
        StringBuilder h = new StringBuilder();
        h.append("HTTP/1.1 ").append(status).append("\r\n");
        h.append("Content-Type: ").append(mime.replace('\r', ' ').replace('\n', ' ')).append("\r\n");
        h.append("Access-Control-Allow-Origin: *\r\n");
        h.append("Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n");
        h.append("Access-Control-Allow-Headers: Range\r\n");
        h.append("Access-Control-Expose-Headers: Content-Length, Content-Range, Accept-Ranges\r\n");
        h.append("Accept-Ranges: bytes\r\n");
        if (length >= 0) h.append("Content-Length: ").append(length).append("\r\n");
        else h.append("Transfer-Encoding: chunked\r\n");
        h.append("Connection: close\r\n\r\n");
        return h;
    }

    private static void proxyRespond(OutputStream out, String status, String mime, byte[] body, String method) throws IOException {
        StringBuilder h = proxyHeaders(status, mime, body.length, method);
        out.write(h.toString().getBytes(StandardCharsets.ISO_8859_1));
        if (!"HEAD".equalsIgnoreCase(method)) out.write(body);
    }

    private static String status(int code) {
        switch (code) {
            case 200: return "200 OK";
            case 206: return "206 Partial Content";
            case 301: return "301 Moved Permanently";
            case 302: return "302 Found";
            case 403: return "403 Forbidden";
            case 404: return "404 Not Found";
            default: return code + " Upstream Response";
        }
    }

    private static void respond(OutputStream out, String status, String mime, byte[] body, String method) throws IOException {
        StringBuilder h = new StringBuilder();
        h.append("HTTP/1.1 ").append(status).append("\r\n");
        h.append("Content-Type: ").append(mime).append("\r\n");
        h.append("Content-Length: ").append(body.length).append("\r\n");
        h.append("Connection: close\r\n\r\n");
        out.write(h.toString().getBytes(StandardCharsets.ISO_8859_1));
        if (!method.equals("HEAD")) out.write(body);
    }

    /** Single-segment VOD playlist; the relative URL resolves against the
     *  manifest's own origin (the same loopback server). */
    private static String playlist(String token) {
        return "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-PLAYLIST-TYPE:VOD\n#EXT-X-TARGETDURATION:3600\n#EXTINF:3599.0,\n"
            + token + "\n#EXT-X-ENDLIST\n";
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
            if (sb.length() > 4096) break;
        }
        return sb.length() == 0 ? null : sb.toString();
    }
}
