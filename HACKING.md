# Elsnime Developer & Hacking Guide

This document covers Elsnime's internals: the hybrid architecture, platform integration, and caching engine.

---

## Architecture Overview

Elsnime is structured as a **hybrid application**:

```
+-------------------------------------------------------------+
|                     VANILLA JS FRONTEND                     |
| - HTML5 views (ui.html)                                     |
| - View Routing & Controllers (js/core.js, js/player.js...)  |
| - Plyr + HLS.js HTML5 Video Player                          |
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

1. **Frontend (View Layer)**: A vanilla HTML5 SPA running in a performance-tuned `WebView`. Resides entirely within `app/src/main/assets/`.
2. **Backend (Data Layer)**: Native Android classes running on a background thread pool, using an embedded SQLite database (`HistoryDb`) for watch history and bounded metadata caching.

---

## The JS-to-Java Communication Bridge

Communication is fully asynchronous to avoid blocking the UI. Scraping and DB accesses run on a background thread.

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

---

## Caching System & SQLite Storage

Elsnime implements an aggressive caching strategy using an **SQLite SQLiteOpenHelper** (`HistoryDb` in `MainActivity.java`) to stay within API rate limits on Jikan and AniList, and to avoid hitting Cloudflare on AniDB repeatedly.

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

Several system-level interventions between `js/player.js` and `MainActivity.java` provide a native-like streaming experience.

### 1. Native Rotation Override

Standard Android orientation changes recreate the `Activity` from scratch, which would reset the WebView state and interrupt video streams.
* To prevent this, the `AndroidManifest.xml` explicitly defines:
  ```xml
  android:configChanges="orientation|screenSize|smallestScreenSize|keyboardHidden|uiMode"
  ```
* This routes orientation changes to `onConfigurationChanged` in `MainActivity.java`, allowing JS to re-evaluate the viewport and push custom layout updates safely.

### 2. Auto-Rotate Fullscreen Loop
On phone-sized devices (`max-width: 899px`), landscape rotation triggers landscape fullscreen playback, and returning to portrait exits. The JS side calls `AndroidApi.setOrientation(mode)` on the bridge:
* `"landscape"`: Locks orientation into sensor landscape.
* `"sensor"`: Shifts back to full sensor tracking (allowing the physical rotation back to portrait to be detected).
* `"auto"`: Resets layout tracking back to the user's default screen orientation.

### 3. Gesture Seeking
Plyr's default tap handles are disabled (`clickToPlay: false`). Custom handlers in `js/player.js` handle single-tap play/pause and double-tap seeking.
* Double-tapping the left 50% of the viewport triggers a rewind (`seek(-10)`).
* Double-tapping the right 50% of the viewport triggers a fast-forward (`seek(10)`).
* A single tap checks for subsequent double-taps within a `300ms` window. If none are registered, play/pause state is toggled.

### 4. Background Buffering Prevention

WebViews often continue buffering video streams when hidden or when the screen is locked, leading to excessive cellular data usage.
* When navigating away from the `'player'` view via `showView()`, `stopPlayback()` is invoked on the frontend.
* This destroys the Plyr instance, tears down the HLS.js streaming worker, empties the `<video>` element's `src` attribute, and calls `.load()` on the video node to release connection pools and cancel downstream network buffers.
* `MainActivity.onPause()` also executes a fallback check to immediately pause any ongoing background HTML5 playback:
  ```java
  web.evaluateJavascript("(function(){var v=document.getElementById('video');if(v&&!v.paused)v.pause();})()", null);
  ```

---

## Scraping Engine & Network Transport

The backend scraping layer is in `AniDbScraper.java`. 

### Cloudflare Detection

The scraper parses AniDB's HTML directly. If a page response contains `"Just a moment"`, the parser throws an `IOException` with the message `"AniDB blocked this request (Cloudflare challenge). Try again in a moment."` The JS layer catches this and notifies the user.

### Network Transport Stack

Elsnime injects a pluggable `HttpTransport` layer backed by **Google Cronet** instead of Android's standard networking stack. It provides a consistent TLS fingerprint matching modern Chrome, preventing scraping requests from being flagged by strict CDN security policies, and coordinates connection pooling and HTTP/3 support when resolving streams.
