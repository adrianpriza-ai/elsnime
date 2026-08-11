# Elsnime Developer & Hacking Guide

Elsnime's internals: architecture, project layout, JS-to-Java bridge, downloads engine, platform integration, and caching.

---

## Architecture Overview

A vanilla JS frontend inside an Android WebView, backed by native Java classes.

```
+--------------------------------------------------------------+
|                     VANILLA JS FRONTEND                      |
|  - HTML5 views (ui.html)                                     |
|  - View Routing & Controllers (js/boot.js, js/player.js...)  |
|  - Plyr HTML5 Video Player (HLS via hls.js)                  |
|  - Downloads tab (js/downloads.js)                           |
+--------------------------------------------------------------+
                               │
           window.AndroidApi   │   window.__androidResponse
           (JavascriptBridge)  │   (JS evaluation)
                               ▼
+--------------------------------------------------------------+
|                      NATIVE JAVA BACKEND                     |
| - MainActivity (Host, AndroidApi bridge, Backend, HistoryDb) |
| - AniDbScraper (Parsing, Metadata enrichment, API calls)     |
| - Downloader (HLS → MP4 download engine)                     |
| - CronetTransport (HTTP networking)                          |
+--------------------------------------------------------------+
```

Frontend lives in `app/src/main/assets/`. Backend classes run on a background thread pool with an embedded SQLite database (`HistoryDb`, an inner class of `MainActivity`) for watch history and bounded metadata caching.

---

## Project Structure

```
app/src/main/
├── AndroidManifest.xml            # permissions, configChanges (orientation etc.)
├── assets/                        # the entire frontend — no build step or bundler
│   ├── ui.html                    # single-page shell, all views
│   ├── css/                       # 13 stylesheets, one per view/component
│   ├── js/                        # 8 plain-JS modules (no framework)
│   └── vendor/                    # vendored third-party libs (no CDN dependency)
└── java/com/elsnime/
    ├── MainActivity.java          # WebView host, AndroidApi bridge, Backend, HistoryDb
    ├── AniDbScraper.java          # scraping, metadata enrichment, stream resolution
    ├── CronetTransport.java       # HttpTransport implementation (Cronet)
    └── Downloader.java            # HLS segment downloader + MP4 remuxer
```

### Frontend modules (`assets/js/`)

| File | Responsibility |
|-|-|
| `boot.js` | Startup, view routing, keyboard shortcuts (incl. `D` → Downloads), one-time setup toasts |
| `core.js` | `androidRequest()` bridge helper, `showToast()`, shared utilities, `playInMpvNative()` |
| `home.js` | Home dashboard: trending, continue-watching row |
| `search.js` | Fuzzy search, category chips |
| `detail.js` | Series pages, episode grid, per-episode + Download All wiring |
| `player.js` | Plyr + hls.js wiring, skip buttons, resume, MPV launch, error mapping |
| `history.js` | Watch history view |
| `downloads.js` | Downloads tab: queue state, progress rendering, localStorage persistence, `__downloadEvent` handling |

### Vendored assets (`assets/vendor/`)

Plyr and hls.js are bundled inside the APK so the player boots with no network: `plyr.css`/`plyr.min.js` (Plyr **3.8.4**) and `hls.min.js` (hls.js **v1**). `ui.html` references them via relative `vendor/` paths — **zero CDN references** at load time. The UI font is Android's built-in `sans-serif` (Roboto).

### Build configuration

- `minSdk 23` (Android 6.0), `targetSdk 35`, `compileSdk 35`
- `app/build.gradle` applies the release `signingConfig` only when `ANDROID_KEYSTORE_FILE` is set — debug builds work with no keystore. For a signed release build, set `ANDROID_KEYSTORE_FILE`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD` (values only matter for release).

---

## The JS-to-Java Communication Bridge

Communication is asynchronous; scraping, DB accesses, and downloads run on background threads.

### 1. Frontend-to-Backend (`AndroidApi.request`)

Frontend API calls use the `androidRequest()` utility in `js/core.js`. Inside the app, `window.AndroidApi` is injected; in a browser it falls back to a `fetch` loop.

```javascript
let androidRequestId = 0;
const androidRequests = new Map();

// Global callback dispatched by Java when a response is resolved
window.__androidResponse = (id, payload) => {
  const pending = androidRequests.get(id);
  if (!pending) return;
  androidRequests.delete(id);
  try { pending(JSON.parse(payload)); } catch (_) { pending({}); }
};

function androidRequest(method, path, body) {
  return new Promise(resolve => {
    const id = String(++androidRequestId);
    androidRequests.set(id, resolve);
    window.AndroidApi.request(id, method, path, body ? JSON.stringify(body) : '');
  });
}
```

```java
@JavascriptInterface
public void request(final String id, final String method, final String path, final String body) {
    backend.executor.execute(() -> {
        // Handle database or scraping in background threads
        String result = backend.handle(method, path, body);

        // Post response back to the main UI Thread for WebView execution
        view.post(() -> view.evaluateJavascript(
            "window.__androidResponse(" + JSONObject.quote(id) + "," + JSONObject.quote(result) + ")",
            null
        ));
    });
}
```

### 2. Complete `AndroidApi` bridge surface

All methods are `@JavascriptInterface` on the inner `AndroidApi` class in `MainActivity.java`. Every async method follows the same `(id, ...)` pattern: it runs on `backend.executor`, then posts `window.__androidResponse(id, json)` back to the WebView.

| Method | Args | Purpose |
|-|-|-|
| `request` | `id, method, path, body` | Generic API: scraping, search, metadata, DB ops |
| `systemTheme` | — | Real device theme (WebView's `prefers-color-scheme` is unreliable) |
| `setFullscreen` | `on` | Immersive bars + landscape rotation for player fullscreen |
| `saveProgress` | `id, animeId, animeTitle, episode, progress, duration, thumbnail, force` | Persist watch position |
| `refreshCache` | `prefixes` | Scoped cache invalidation (`GLOB` prefix deletion) |
| `mpvStatus` | `id` | Whether mpv-android / mpv CLI is available |
| `playInMpv` | `id, animeId, animeTitle, episode, type, referer, userAgent` | Launch current episode in mpv |
| `startDownload` | `id, uid, animeId, animeTitle, episode, type, quality` | Enqueue a download |
| `cancelDownload` | `id, animeId, episode` | Cancel an active download |
| `removeDownload` | `id, animeId, animeTitle, episode` | Delete a finished file |
| `listDownloads` | `id` | Enumerate files on disk (reconciles queue state) |
| `clearDownloads` | `id` | Cancel all active downloads + delete everything under `Movies/Elsnime` |

### 3. Backend-to-Frontend push events

The download engine pushes events into the WebView without polling: Java emits `window.__downloadEvent(json)`; `js/downloads.js` listens and updates the queue UI in place.

---

## Downloads Engine

`Downloader.java` turns HLS (m3u8) streams into standalone files under `/storage/emulated/0/Movies/Elsnime/<anime>/`, named `<anime> - Episode <n>.mp4` (`.ts` fallback). Android's `DownloadManager` can't handle HLS, so Elsnime implements its own pipeline.

### Concurrency model

- A **single worker thread** (`Executors.newSingleThreadExecutor()`) processes tasks one at a time; the queue is a `ConcurrentHashMap` keyed by `animeId + episode`.
- Each `Task` has a `volatile boolean cancelled` flag; the worker checks it between segments and aborts cleanly, deleting the temp file.
- Progress events are emitted from the worker thread; the listener posts them to the main thread via `view.post(evaluateJavascript(...))`.

### Pipeline (`run(Task)`)

1. **Resolve** — reuses `AniDbScraper` stream resolution with the CDN's referer + browser UA. If a master playlist and target quality are available, `pickVariant()` picks the rendition closest to the **Default Quality** setting.
2. **Fetch manifest** — parses `#EXTINF` segments; **AES-128 encrypted** playlists (`#EXT-X-KEY:METHOD=AES-128`) are decrypted per-segment (CBC, PKCS5/PKCS7 padding, IV from the key URI or media sequence). fMP4 playlists (`#EXT-X-MAP`) are rejected with a clear error.
3. **Download segments** — fetched sequentially, appended to a temp file, emitting `downloading` events with `segDone/segTotal/bytes`.
4. **Remux** — the concatenated MPEG-TS is remuxed to **MP4** via `MediaExtractor` + `MediaMuxer`. If a stream can't be muxed (exotic codecs), the raw `.ts` file is kept.
5. **Store** — on **API 29+**, files are written through `MediaStore` (`IS_PENDING=1` → write → `IS_PENDING=0`, `RELATIVE_PATH=Movies/Elsnime/<anime>/`) with **no permission required**. On **API 23–28**, legacy `WRITE_EXTERNAL_STORAGE` is requested once and files are written via the direct path.

### Event states & disk reconciliation

States: `resolving` → `downloading` → `remuxing` → `done` (with stored file name + size), or `error (msg)` / `cancelled`. The `done` event's `fileName` flips episode icons to ✓ and persists the queue in `localStorage`.

`listDownloads()` queries MediaStore (API 29+) or the direct path (≤28) so the Downloads tab reflects what's on disk — stale queue entries left by a killed process are cleaned up on boot. `deleteAll()` cancels active tasks and sweeps every entry under `Movies/Elsnime`.

---

## Custom Playback, Gestures & System Overrides

### 1. Native Rotation Override

Standard Android orientation changes recreate the `Activity`, resetting the WebView and interrupting streams. The manifest sets `android:configChanges="orientation|screenSize|smallestScreenSize|keyboardHidden|uiMode"`, routing rotations to `onConfigurationChanged` so JS can re-evaluate the viewport. Fullscreen explicitly rotates to landscape (`setRequestedOrientation(SCREEN_ORIENTATION_SENSOR_LANDSCAPE)` inside `AndroidApi.setFullscreen`); leaving restores `SCREEN_ORIENTATION_UNSPECIFIED`. Neither rotation ever recreates the activity or interrupts playback.

### 2. Control-Bar-Driven Playback & Fullscreen

Playback is driven entirely by the Plyr control bar (no custom tap gestures or overlay buttons). The player box uses a 16:9 CSS placeholder on `#video-wrap` that `js/player.js` replaces with the video's real aspect ratio once metadata loads.

**Fullscreen** runs in Plyr's CSS fallback mode (`fullscreen: { fallback: 'force' }`). The JS Fullscreen API targets the `.plyr` container, which Android WebView silently ignores — it only honors native fullscreen on the `<video>` element via `onShowCustomView`, which would hide the WebView and its controls entirely. Forcing the fallback keeps the player inside the WebView with the control bar overlaid, so the Plyr UI (including quality/speed menus) stays reachable. `js/player.js` listens for Plyr's `enterfullscreen`/`exitfullscreen` and calls `AndroidApi.setFullscreen(boolean)`, which hides the status/nav bars (`applyImmersive`) and rotates to landscape for the duration, restoring both on exit. The Android back button exits fullscreen first via `window.handleAppBack` in `js/boot.js` (it checks `player.fullscreen.active`), popping the player view on the next press.

HLS streams are played with **hls.js wired manually** in `loadStream` (not through Plyr's built-in hls option) so the app controls the instance: the backend's `master` playlist URL feeds `new Hls()` via `loadSource` + `attachMedia`, and the **settings > quality** menu switches `hls.currentLevel` via the `quality: { forced, onChange }` config. **settings > speed** covers playback rate.

### 3. Background Buffering Prevention

WebViews often keep buffering when hidden or screen-locked, burning cellular data. Leaving the `'player'` view runs `stopPlayback()`: it destroys the Plyr instance, empties the `<video>` src, and calls `.load()` to release connection pools and cancel downstream buffers. `MainActivity.onPause()` also pauses any ongoing background HTML5 playback as a fallback.

### 4. Resume Playback

Reopening an episode resumes from the saved position. `startWebPlayer` reads the episode's saved progress from history **before** `saveProgress(ep, 0, 0, true)` resets the row, then seeks on `loadedmetadata`. Resume is skipped for the first 15 seconds, within 10 seconds of the end, or past the 0.9 progress ratio the episode grid labels "Watched".

### 5. CDN Headers & Error Mapping

CDN-gated streams need the same fingerprint the scraper used:

- **User-Agent**: `MainActivity` sets the WebView's global UA to `AniDbScraper.UA`, so hls.js XHR requests carry the matching UA (JS can't override the UA on XHR — forbidden header).
- **Referer**: stamped per-request in hls.js's `xhrSetup` config.

On fatal hls.js errors, `hlsFatalMessage()` maps failures to specific messages:

- `manifestLoadError`/`levelLoadError`/`fragLoadError` with HTTP **401/403** → "The CDN blocked this stream (HTTP xxx) — try opening in MPV."
- **404** → "Stream not found — it may have expired…"
- Other HTTP codes → "Stream request failed (HTTP xxx)…"
- No response (pure network failure) → "Network error while loading the stream — check your connection…"
- `manifestParsingError` → "The stream playlist is invalid…"
- `MEDIA_ERROR` (decode) → "Could not decode this video…"

---

## MPV Integration (ani-cli's Termux flow)

External playback mirrors [ani-cli](https://github.com/pystardust/ani-cli)'s `android_mpv` function.

### Detection (`Backend.mpvAppPackage`)

Checks both mpv-android package IDs — `is.xyz.mpv` (current, renamed in v1.4.0) and legacy `is.mpv.android` — via `PackageManager`, both declared in the manifest's `<queries>` so Android 11+ package visibility lets the lookups succeed. `mpvStatus()` reports availability to the frontend, which shows/hides the "Open in MPV" button and the MPV player option.

### Launch (`Backend.playInMpv`)

1. Resolves the episode's stream through the scraper (same referer/UA as the WebView).
2. mpv-android installed → **`launchMpvApp`**: an `ACTION_VIEW` intent with the stream URL and a `title` extra (`"<Anime> - Episode <n>"`) targeting `MPVActivity` — exactly ani-cli's `am start -a VIEW -d <url> -e title <title>`.
3. Else, Termux `mpv` CLI exists (rooted) → **`launchMpv`**: execs it with `--no-stdin --tls-verify=no --force-media-title=`.
4. Otherwise returns `{"error": "MPV is not installed…"}`.

### Header Injection (`writeMpvFlags`)

mpv-android can't receive HTTP headers via intents, so `writeMpvFlags` passes them through the config-include mechanism (ani-cli's exact approach): it writes `tls-verify=no`, `user-agent`, and `http-header-fields=Referer: ...` to `/storage/emulated/0/mpv/mpv.config.mp4` (sanitized against line breaks/`;` injection). The user must add one line to mpv.conf:

```
include='/storage/emulated/0/mpv/mpv.config.mp4'
```

Unlike ani-cli's `cleanup` trap, the config file is intentionally left in place — it's overwritten on every launch, and stale values only affect manual mpv usage outside the app.

---

## Caching System & SQLite Storage

Elsnime uses **SQLiteOpenHelper** (`HistoryDb` in `MainActivity.java`) to stay within API rate limits on Jikan and AniList, and to avoid repeated Cloudflare hits on AniDB.

### Cache Mechanics (DB Schema Version 3)

- **Table**: `cache(key TEXT PRIMARY KEY, value TEXT NOT NULL, expires_at INTEGER NOT NULL, last_updated INTEGER NOT NULL DEFAULT 0)`
- **Limit**: Capped at **250 entries** (`MAX_CACHE_ENTRIES`).
- **LRU**: When a write (`cachePut`) pushes the count past the cap, rows sorted by `last_updated ASC` are deleted oldest-first.
- **Sweeping**: `maybeSweep()` runs opportunistically, throttled to **5-minute intervals**, deleting expired keys without blocking active threads.

### Scoped Invalidation (Pull-to-Refresh)

`refreshCache(prefixes)` drops only the active view's cache keys via `GLOB` prefix matching:

```java
void cacheClearPrefix(String prefix) {
    getWritableDatabase().delete("cache", "key GLOB ?", new String[]{ prefix + "*" });
}
```

**Prefix mappings (`js/boot.js`):**

- **Home tab**: Clears `"trending"`
- **Search (no query)**: Clears `"trending,tags,tag|"`
- **Search (with query)**: Clears `"anidb-search|,anidb-resolve|"`
- **History tab**: No cache invalidation needed.

---

## Scraping Engine & Network Transport

The backend scraping layer is in `AniDbScraper.java`.

### Cloudflare Detection

The scraper parses AniDB's HTML directly. A response containing `"Just a moment"` throws an `IOException` — `"AniDB blocked this request (Cloudflare challenge). Try again in a moment."` — which the JS layer catches and surfaces to the user.

### Network Transport Stack

Elsnime injects a pluggable `HttpTransport` layer backed by **Google Cronet** (`CronetTransport.java`) instead of Android's standard stack. It provides a consistent TLS fingerprint matching modern Chrome (avoiding CDN flags on scraping requests), plus connection pooling and HTTP/3 support when resolving streams.
