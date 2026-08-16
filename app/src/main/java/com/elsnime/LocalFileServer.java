package com.elsnime;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
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
