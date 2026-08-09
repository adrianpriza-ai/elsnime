# Elsnime Developer & Hacking Guide

This document covers Elsnime's internals: the hybrid architecture, platform integration, and caching engine.

---

## Architecture Overview

Elsnime runs a vanilla JS frontend inside an Android WebView, backed by native Java classes.

```
+-------------------------------------------------------------+
|                     VANILLA JS FRONTEND                     |
| - HTML5 views (ui.html)                                     |
| - View Routing & Controllers (js/core.js, js/player.js...)  |
| - Plyr HTML5 Video Player (HLS via hls.js)                  |
+-------------------------------------------------------------+
                               │
           window.AndroidApi   │   window.__androidResponse
           (JavascriptBridge)  │   (JS evaluation)
                               ▼
+-------------------------------------------------------------+
|                      NATIVE JAVA BACKEND                    |
| - MainActivity (Host, configuration, orientation overrides) |
| - AniDbScraper (Parsing, Metadata enrichment, API calls)    |
| - HistoryDb (SQLite local database cache + history)         |
| - CronetTransport (HTTP networking)                         |
+-------------------------------------------------------------+
```

Frontend lives in `app/src/main/assets/`. Backend classes run on a background thread pool with an embedded SQLite database (`HistoryDb`) for watch history and bounded metadata caching.

---

## The JS-to-Java Communication Bridge

Communication is asynchronous; scraping and DB accesses run on a background thread.

### 1. Frontend-to-Backend (`AndroidApi.request`)

Frontend API calls use the `api` utilities in `js/core.js`. Inside the Android app, `window.AndroidApi` is injected. In a standard browser, it falls back to a `fetch` loop (runnable via `app.py`).

**Sending a Request from JavaScript:**
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

**Receiving and Resolving inside Java (`MainActivity.java`):**
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

Other bridge methods on `AndroidApi` (`MainActivity.java`): `systemTheme()` (real device theme), `setFullscreen(on)` (fullscreen immersive bars + landscape rotation), `saveProgress(...)`, `refreshCache(prefixes)`, `mpvStatus(id)`, and `playInMpv(...)`.

---

## Caching System & SQLite Storage

Elsnime uses an **SQLite SQLiteOpenHelper** (`HistoryDb` in `MainActivity.java`) to stay within API rate limits on Jikan and AniList, and to avoid hitting Cloudflare on AniDB repeatedly.

### Cache Mechanics (DB Schema Version 3)
* **Table structure**: `cache(key TEXT PRIMARY KEY, value TEXT NOT NULL, expires_at INTEGER NOT NULL, last_updated INTEGER NOT NULL DEFAULT 0)`
* **Enforced Limit**: Capped at **250 entries** (`MAX_CACHE_ENTRIES`).
* **Replacement Strategy (LRU)**: When a write (`cachePut`) causes the DB count to exceed `MAX_CACHE_ENTRIES`, rows are sorted by `last_updated ASC` and the oldest entries are immediately deleted.
* **Sweep-Scheduling**: A background cleaning routine (`maybeSweep()`) runs opportunistically, throttled to a minimum of **5-minute intervals**, physically deleting expired keys without blocking active threads.

### Scoped Invalidation (Pull-to-Refresh)

Pulling to refresh maps active components to specific cache prefixes. The Java layer exposes `refreshCache(prefixes)` which uses `GLOB` wildcard matching to selectively drop keys:

```java
void cacheClearPrefix(String prefix) {
    getWritableDatabase().delete("cache", "key GLOB ?", new String[]{ prefix + "*" });
}
```

**Prefix Mappings in JavaScript (`js/boot.js`):**
- **Home tab**: Clears `"trending"`
- **Search view (without query)**: Clears `"trending,tags,tag|"`
- **Search view (with query)**: Clears `"anidb-search|,anidb-resolve|"`
- **History tab**: No cache invalidation needed.

---

## Custom Playback, Gestures & System Overrides

Several system-level interventions between `js/player.js` and `MainActivity.java` shape the playback experience.

### 1. Native Rotation Override

Standard Android orientation changes recreate the `Activity` from scratch, resetting the WebView state and interrupting video streams.
* The `AndroidManifest.xml` explicitly defines:
  ```xml
  android:configChanges="orientation|screenSize|smallestScreenSize|keyboardHidden|uiMode"
  ```
* This routes orientation changes to `onConfigurationChanged` in `MainActivity.java`, letting JS re-evaluate the viewport and push custom layout updates.
* Entering fullscreen explicitly rotates the activity to landscape (`setRequestedOrientation(SCREEN_ORIENTATION_SENSOR_LANDSCAPE)` inside `AndroidApi.setFullscreen`); leaving restores `SCREEN_ORIENTATION_UNSPECIFIED`. Because `configChanges` is set, neither rotation ever recreates the activity or interrupts playback.

### 2. Control-Bar-Driven Playback & Fullscreen
Playback is driven entirely by the Plyr control bar (no custom tap gestures or overlay buttons). The player box uses a 16:9 CSS placeholder on `#video-wrap` that `js/player.js` replaces with the video's real aspect ratio once metadata loads.

**Fullscreen** runs in Plyr's CSS fallback mode (`fullscreen: { fallback: 'force' }` in the Plyr config inside `loadStream`). The JS Fullscreen API targets the `.plyr` container, which Android WebView silently ignores — it only honors native fullscreen on the `<video>` element via `onShowCustomView`, which would hide the WebView and its controls entirely. Forcing the fallback keeps the player inside the WebView with the control bar overlaid, so the Plyr UI (including the quality/speed menus) stays reachable in fullscreen. Plyr fires `enterfullscreen`/`exitfullscreen` on `player.elements.container`; `js/player.js` listens for them and calls the `AndroidApi.setFullscreen(boolean)` bridge, which hides the status/nav bars (`applyImmersive`) and rotates to landscape (`SCREEN_ORIENTATION_SENSOR_LANDSCAPE`) for the duration, restoring both on exit. The Android back button exits fullscreen first via `window.handleAppBack` in `js/boot.js` (it checks `player.fullscreen.active`), popping the player view on the next press.

HLS (m3u8) streams are played with hls.js, wired manually in `loadStream` (Plyr 3.6+ has no built-in hls.js support): the backend's `master` playlist URL is fed to `new Hls()` via `loadSource` + `attachMedia`, and the **settings > quality** menu is driven by the `quality: { forced, onChange }` config (menu picks switch `hls.currentLevel`). **settings > speed** covers playback rate.

### 3. Background Buffering Prevention

WebViews often continue buffering video streams when hidden or when the screen is locked, burning cellular data.
* When navigating away from the `'player'` view via `showView()`, `stopPlayback()` runs on the frontend.
* This destroys the Plyr instance (`player.destroy()`), tearing down the stream, emptying the `<video>` element's `src` attribute, and calling `.load()` on the video node to release connection pools and cancel downstream network buffers.
* `MainActivity.onPause()` also executes a fallback check to pause any ongoing background HTML5 playback:
  ```java
  web.evaluateJavascript("(function(){var v=document.getElementById('video');if(v&&!v.paused)v.pause();})()", null);
  ```

### 4. Resume Playback
Reopening an episode resumes from the saved watch position. `js/player.js`'s `startWebPlayer` reads the episode's saved progress from history (`savedResumePosition` → `getHistoryMap` → `/api/history`) **before** `saveProgress(ep, 0, 0, true)` resets the row, then seeks the video on `loadedmetadata` once the duration is known. Resume is skipped for the first 15 seconds, within 10 seconds of the end, or past the 0.9 progress ratio the episode grid labels "Watched".

---

## Scraping Engine & Network Transport

The backend scraping layer is in `AniDbScraper.java`.

### Cloudflare Detection

The scraper parses AniDB's HTML directly. If a page response contains `"Just a moment"`, the parser throws an `IOException` with the message `"AniDB blocked this request (Cloudflare challenge). Try again in a moment."` The JS layer catches this and notifies the user.

### Network Transport Stack

Elsnime injects a pluggable `HttpTransport` layer backed by **Google Cronet** instead of Android's standard networking stack. It provides a consistent TLS fingerprint matching modern Chrome, preventing scraping requests from being flagged by strict CDN security policies, and coordinates connection pooling and HTTP/3 support when resolving streams.
