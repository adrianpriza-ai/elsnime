# Handoff — Session Changes (Elsnime Android)

Handoff for the next agent. Everything below was implemented in this session. The
app is an Android **WebView** single-page app: `app/src/main/assets/ui.html` +
vanilla JS (`js/*.js`) on the front, and `app/src/main/java/com/elsnime/` (a single
`MainActivity.java` with an embedded `AniDbScraper` + SQLite `HistoryDb`) on the
back. Frontend talks to the backend through a `window.AndroidApi` JS bridge
(`@JavascriptInterface`), with plain `fetch()` fallbacks for dev mode.

---

## 1. UI — player & anime-info page made minimal (YouTube-style)

Files: `ui.html`, `css/base.css`, `css/youtube.css`, `css/player.css`, `js/player.js`

- **Removed the fake Like button** from the player (`#btn-like` gone from
  `ui.html`; `toggleLike()` and its score-mirroring block removed from
  `player.js`; `.action-btn.liked` rule removed).
- **Description box is borderless/backgroundless** — `.yt-desc-box` is now plain
  text; the `#detail-desc::after` fade now targets `--bg-primary`.
- **Action buttons are transparent** until hovered (YouTube style).
- **Detail hero flattened** (no rounded card).

## 2. Theme — dark = YouTube dark, auto follows system

Files: `css/base.css`, `js/core.js`, `MainActivity.java`, `AndroidManifest.xml`, `ui.html`

- Dark palette is now **neutral black/gray, no blue tint**:
  `--bg-primary:#0f0f0f`, `--surface:#212121`, `--surface-2:#272727`,
  `--text-primary:#f1f1f1`, `--text-secondary:#aaaaaa`, `--text-muted:#717171`,
  `--bg-hover`/`--border` = `rgba(255,255,255,0.1)`, accent hue 206 (YouTube blue).
  Keep these in sync between `base.css :root` and `core.js applyTheme('dark')`.
- **Auto theme works via a native bridge** (WebView `prefers-color-scheme` is
  unreliable — the old `FORCE_DARK_AUTO` approach was removed):
  - `AndroidApi.systemTheme()` returns `"dark"`/`"light"` from the system
    `uiMode` — works on every API level.
  - `core.js systemIsDark()` prefers the native call, falls back to
    `matchMedia('(prefers-color-scheme: dark)')` for dev mode.
  - `MainActivity.onConfigurationChanged` pushes `window.__systemThemeChanged()`
    to JS on `uiMode`/orientation changes.
  - **`AndroidManifest.xml` MUST keep** `android:configChanges="orientation|screenSize|smallestScreenSize|keyboardHidden|uiMode"` — without it, rotation/theme changes recreate the activity and kill playback.
  - `ui.html` has `<meta name="color-scheme" content="dark light" />` and
    `base.css :root { color-scheme: dark }` so WebView never algorithmically
    darkens the page.

## 3. Player — fullscreen/auto-rotate, gestures, stream teardown

Files: `js/player.js`, `css/player.css`, `ui.html`, `js/boot.js`, `MainActivity.java`, `AndroidManifest.xml`

- **Custom fullscreen** (not Plyr's): `body.video-fullscreen` makes `#video-wrap`
  `position:fixed` over the whole app (bottom nav hidden). Native orientation is
  driven by `AndroidApi.setOrientation(mode)` (`"landscape"` → `"sensor"` after
  600ms → `"auto"` on exit), so rotating back to portrait exits fullscreen.
- **Auto-flip**: on phones (`max-width:899px`), rotating to landscape enters
  fullscreen, portrait exits. Desktop/tablets use the button only.
- **Floating overlay buttons** in `#video-wrap`: skip back/forward ±10s and
  fullscreen (`.ov-btn`, fade in/out with Plyr's control bar via
  `controlsshown`/`controlshidden` events).
- **Double-tap to seek ±10s** (left/right half of the video) with a
  `#seek-indicator` time overlay; single tap toggles play. Plyr is initialized
  with `clickToPlay:false` and `fullscreen:{enabled:false}` because we own those
  gestures. Tap handler ignores `.plyr__control`, `.plyr__controls`,
  `[data-action]`; a pending single-tap toggle is cancelled if the user presses
  a control within 300ms.
- **Stream stops when leaving the player**: `showView()` in `core.js` calls
  `stopPlayback()` whenever the active view leaves `'player'` — this destroys
  the HLS/Plyr instances, pauses, clears the `<video>` src and calls `load()`
  (aborts buffering). `pushView('player')` early-returns on the same view, so
  episode switching never tears down playback.
- `MainActivity.onPause()` also pauses the web video (saves data in background).
- `boot.js`: `F` key → `toggleFullscreen()`; back button exits fullscreen first,
  then pops the player.

## 4. Cache system — overwrite + bounded + scoped refresh

Files: `MainActivity.java`, `AniDbScraper.java`, `js/boot.js`

- **SQLite `cache` table upgraded to DB v3**: new `last_updated` column
  (migration via `ALTER TABLE ... ADD COLUMN last_updated INTEGER NOT NULL
  DEFAULT 0` guarded by try/catch). `onCreate` creates it directly.
- `cachePut` **overwrites** via `CONFLICT_REPLACE` and stamps `last_updated`; a
  throttled `maybeSweep()` (once/5 min) physically deletes expired rows, and
  `enforceCacheLimit()` caps the table at **250 entries**, evicting
  least-recently-written rows. Both are fail-safe (try/catch) so DB lock
  contention can never fail a request.
- **Scoped pull-to-refresh**: `AndroidApi.refreshCache(prefixes)` (comma-joined;
  `""` = no-op, `"all"` = wipe) replaces the old full-wipe `refresh()`.
  `js/boot.js refreshCurrentView()` passes per-view prefixes
  (home→`trending`; search+query→`anidb-search|,anidb-resolve|`;
  search no-query→`trending,tags,tag|`; history→nothing). Old `refresh()` is
  gone — only `refreshCache` exists now.
- `CacheStore` gained `clearPrefix` (`key GLOB ?`); scraper exposes
  `clearCachePrefix()`. `cachedObject` never caches payloads with an `"error"`
  key.

## 5. Search — was returning empty results (fixed)

File: `AniDbScraper.java` (verified against the live site)

- **Root cause**: `aniDbSearch()` regex expected JSON-escaped quotes (`\"`)
  that the current anidb.app `/browse` page no longer has. The page now uses
  `<a href="https://anidb.app/anime/<slug>-<id>" ... title="Title">`.
- **Fix**: new pattern
  `anime/([a-z0-9-]+-\d+)"[^>]*title="([^"]+)"` (CASE_INSENSITIVE), plus a
  `page.replace("\\\"","\"")` normalize step for any JSON-escaped markup.
  Verified with a compiled Java test: **17/17** cards matched on the live page.
  `id` = full `<slug>-<numericId>` (e.g. `naruto-3686`); `numericId()` extracts
  the number used in `/api/frontend/anime/<id>/episodes`.
- **`cachedArray` self-heals**: cached empty arrays are treated as a miss and
  empty results are never stored — a transient failure or the old broken regex
  can no longer poison a query's cache for a day.
- Cloudflare retry bumped 2 → 3 attempts (backoff 1.2s/2.4s). Cloudflare
  challenges happen intermittently; the app's Cronet transport usually passes.

## 6. Search UX — genre chips & clearable filter tags

Files: `js/search.js`, `js/home.js`, `js/boot.js`, `js/core.js`, `ui.html`, `css/search.css`

- **Genre chips no longer fill the search box**; `activeGenreChip()` tracks the
  selected chip so the filter survives tab switches, refresh, and back.
- **`#active-filter` tag** above the results shows either `Genre: X ×` (chip) or
  `Search: X ×` (box text) and clears the active filter via
  `clearGenreFilter()` / `clearQueryFilter()`. **Deliberately no animations or
  glow** — flat, instant (the only transition was removed).
- **Stale-response guard**: `searchSeq` is bumped on every search/clear;
  `doSearch` ignores responses whose seq is stale, and `resetSearchToTrending()`
  cancels the debounce timer + invalidates in-flight requests. This prevents
  stale results rendering over the trending grid.
- `escapeGenreName()` is used for all genre/query/error text inserted via
  `innerHTML` (chips included — XSS-safe).
- `boot.js handleAppBack` clears both filters; `refreshCurrentView` re-runs the
  active chip search on pull-to-refresh.

---

## 7. Settings — Android-style list (no separators) + MPV fixes

Files: `css/settings.css`, `js/core.js`, `AniDbScraper.java`, `MainActivity.java`

- **Settings screen is now Android-style**: row `border-top` separators removed
  (`.settings-row` is flat), rows are 56px (Android touch target) with a subtle
  `:hover`/`:active` surface tint for press feedback, and group kickers switched
  from uppercase letter-spaced to 14px/500 sentence-case headers aligned to the
  list padding (`0 16px 8px`) — matching the modern Settings app.
- **MPV fixes (three real defects):**
  - `core.js`: mpv was launched with `navigator.userAgent` which is
    `"Elsnime Android"` on device (MainActivity overrides the WebView UA) and a
    hardcoded `anidb.app` referer — the CDN can block that. Now `MPV_UA` (same
    Chrome UA string the scraper uses) is always passed, and the dev-mode
    `/api/mpv` call uses `stream.referer` when present.
  - `AniDbScraper.stream()` now stamps `referer` (the embed page the manifest
    was fetched from) onto the stream JSON, so mpv sends the right `Referer`.
  - `MainActivity`: `mpvCliPath()` resolves the **absolute** mpv binary path;
    the app process PATH never includes Termux's `.../usr/bin`, so
    `ProcessBuilder("mpv", …)` would fail even though detection succeeded.
    `launchMpv` execs the resolved path and `playInMpv` uses the stream referer.
  - Known mpv-android limit (unchanged): intents can't carry referer/UA —
    mpv-android issue #427 requests this; users must set a global referer in
    mpv-android's advanced settings for referer-protected CDNs.

---

## 8. CSS polish — unified colors & shapes (tokens everywhere)

Files: `css/base.css`, `css/cards.css`, `css/history.css`, `css/search.css`,
`css/settings.css`, `css/player.css`, `css/youtube.css`, `js/core.js`

- **New tokens in base.css**: `--track-bg` (progress-track background),
  `--danger-strong` (danger borders), `--danger-hover` (danger hover fill),
  `--on-accent` (text on accent fills). Progress tracks were hardcoded
  `rgba(255,255,255,0.15)` (cards) vs `0.08` (history) — now `--track-bg`,
  themed per mode in `core.js applyTheme` (light: `rgba(0,0,0,0.12)`) so
  tracks are visible in light mode too.
- **Danger reds unified**: the duplicated `rgba(239,68,68,…)` literals
  (0.3/0.18/0.4 alphas) across `#player-error`, `.danger-btn:hover` and
  `.del-btn:hover` now use `--danger-strong`/`--danger-hover`. Fixed dead
  rule: `.del-btn` had `border-color` on hover but no border — added
  `border: 1px solid transparent`.
- **Shapes unified**: settings `.pill-slider`/`.pill-option` are now
  pill-shaped (999px) with a 1px border — identical to the SUB/DUB `.seg-ctrl`
  (previously 8px container + 6px button). Hardcoded radii `10px`, `8px`,
  `4px` (`.yt-ep-row`, `.yt-ep-thumb`, `.detail-hero-poster`, `.yt-ep-chip`,
  `.upnext-tag`, `.seek-indicator`) now use `--radius-*` tokens.
- **Cleanup**: removed the fully-overridden `#video-wrap` block in player.css
  (youtube.css supersedes it); `--plyr-video-control-color` now uses
  `--text-primary` (hover stays `#fff` for the brightness boost over the
  black video). Accent-fill text (`#fff`) uses `--on-accent` everywhere
  (`.chip.active`, `.pill-option.active`, `.yt-play-btn`, hero accent badge).
- Remaining hardcoded colors are deliberate media overlays (scrims, episode
  chips, overlay buttons over video/banner art) — keep them black/white.

---

## Build & validate

```bash
./gradlew assembleDebug        # full Android build (Java + assets)
node --check app/src/main/assets/js/*.js   # JS syntax (all files)
```

All changes in this session pass both. No tests exist; validation is the gradle
build + node checks + code review.

## Platform gotchas (keep in mind)

- `AndroidApi` methods are synchronous from JS for return values
  (`systemTheme()`); void methods run on the bridge thread.
- `@JavascriptInterface` methods must not be called on the UI thread (use
  `view.post(...)`), and long work goes through `backend.executor`.
- Don't re-add Plyr's built-in fullscreen control — it's unreliable in WebView;
  the CSS overlay + `setOrientation` is the intended approach.
- If the AniDB `/browse` page markup changes again, search breaks first —
  re-verify the `aniDbSearch` regex against the live HTML.
- `HistoryDb` is at DB version 3; keep `onUpgrade` idempotent for fresh installs.
