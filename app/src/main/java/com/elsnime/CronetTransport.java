package com.elsnime;

import android.content.Context;
import org.chromium.net.*;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Cronet-backed HTTP transport: uses the Chromium network stack (the same code
 * Chrome runs), so the TLS ClientHello fingerprint (JA3/JA4) is a genuine
 * Chrome one — the Android equivalent of the curl-impersonate binaries that
 * ani-cli uses to pass AniDB's Cloudflare check.
 *
 * If Cronet cannot initialize (e.g. native lib unavailable on a device), the
 * app falls back to {@link AniDbScraper.HttpUrlConnectionTransport} so the
 * scraper keeps working.
 */
final class CronetTransport implements AniDbScraper.HttpTransport {
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124.0.0.0 Safari/537.36";
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final long TIMEOUT_MS = 30_000L;

    static AniDbScraper.HttpTransport create(Context context) {
        try { return new CronetTransport(context); }
        catch (Throwable t) {
            android.util.Log.w("CronetTransport", "Cronet init failed; falling back to HttpURLConnection", t);
            return AniDbScraper.HttpUrlConnectionTransport.INSTANCE;
        }
    }

    private final CronetEngine engine;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    private CronetTransport(Context context) {
        engine = new ExperimentalCronetEngine.Builder(context)
                .setUserAgent(UA)
                .enableHttp2(true)
                .enableQuic(false)
                .enableBrotli(true)
                .build();
    }

    @Override
    public String request(String method, String url, String body, String referer, String origin) throws IOException {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> result = new AtomicReference<>();
        final AtomicReference<IOException> error = new AtomicReference<>();
        final ByteArrayOutputStream sink = new ByteArrayOutputStream();

        UrlRequest.Builder b = engine.newUrlRequestBuilder(url, new UrlRequest.Callback() {
            @Override
            public void onRedirectReceived(UrlRequest r, UrlResponseInfo i, String newLocation) { r.followRedirect(); }

            @Override
            public void onResponseStarted(UrlRequest r, UrlResponseInfo i) { r.read(ByteBuffer.allocateDirect(BUFFER_SIZE)); }

            @Override
            public void onReadCompleted(UrlRequest r, UrlResponseInfo i, ByteBuffer buf) {
                buf.flip();
                byte[] chunk = new byte[buf.remaining()];
                buf.get(chunk);
                sink.write(chunk, 0, chunk.length);
                buf.clear();
                r.read(buf);
            }

            @Override
            public void onSucceeded(UrlRequest r, UrlResponseInfo i) {
                try {
                    // Mirror HttpUrlConnectionTransport exactly: it concatenates
                    // readLine() output without separators, stripping all \r and \n.
                    // The parsers (e.g. the search regex) rely on that, so keep the
                    // two transports behaviorally identical.
                    String res = new String(sink.toByteArray(), StandardCharsets.UTF_8).replace("\r", "").replace("\n", "");
                    AniDbScraper.rejectCloudflare(res);
                    if (i.getHttpStatusCode() >= 400) throw new IOException("HTTP " + i.getHttpStatusCode());
                    result.set(res);
                } catch (IOException e) { error.set(e); }
                latch.countDown();
            }

            @Override
            public void onFailed(UrlRequest r, UrlResponseInfo i, CronetException e) {
                error.set(new IOException(e.getMessage() == null ? "Cronet request failed" : e.getMessage(), e));
                latch.countDown();
            }

            @Override
            public void onCanceled(UrlRequest r, UrlResponseInfo i) {
                error.set(new IOException("Request cancelled"));
                latch.countDown();
            }
        }, executor)
        .setHttpMethod(method)
        .addHeader("User-Agent", UA)
        .addHeader("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8")
        .addHeader("Accept-Language", "en-US,en;q=0.9")
        .addHeader("Referer", referer);
        if (origin != null) b.addHeader("Origin", origin);
        if (body != null) {
            b.addHeader("Content-Type", "application/json");
            b.setUploadDataProvider(new ByteBufferUploadProvider(body.getBytes(StandardCharsets.UTF_8)), executor);
        }
        UrlRequest req = b.build();
        req.start();
        try {
            if (!latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                req.cancel();
                throw new IOException("Request timed out");
            }
        } catch (InterruptedException e) {
            req.cancel();
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
        IOException err = error.get();
        if (err != null) throw err;
        String res = result.get();
        if (res == null) throw new IOException("Request failed");
        return res;
    }

    /** One-shot upload provider for small JSON bodies (POST). */
    private static final class ByteBufferUploadProvider extends UploadDataProvider {
        private final ByteBuffer buffer;
        ByteBufferUploadProvider(byte[] bytes) { buffer = ByteBuffer.wrap(bytes); }
        @Override public long getLength() { return buffer.capacity(); }
        @Override public void read(UploadDataSink sink, ByteBuffer out) {
            if (!buffer.hasRemaining()) { sink.onReadSucceeded(false); return; }
            out.put(buffer);
            sink.onReadSucceeded(buffer.hasRemaining());
        }
        @Override public void rewind(UploadDataSink sink) { buffer.rewind(); sink.onRewindSucceeded(); }
    }
}
