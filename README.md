# Elsnime

[![Android-Lollipop](https://img.shields.io/badge/Android-5.0%2B%20%28API%2021%29-green.svg)](https://developer.android.com)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Frontend-SPA](https://img.shields.io/badge/Frontend-Vanilla%20JS-blue.svg)](app/src/main/assets/ui.html)
[![Backend-Java](https://img.shields.io/badge/Backend-Java%20%2F%20SQLite-orange.svg)](app/src/main/java/com/elsnime)

Elsnime is a lightweight, privacy-focused anime streaming client for Android. Built with a unique **hybrid single-page app (SPA) architecture**, it wraps a highly responsive, modern vanilla CSS/JS frontend inside an Android WebView, backed by a high-performance native Java scraping engine and an SQLite data access layer.

There are no user accounts, no telemetry, no tracking, and no intrusive permission requests.

---

## Core Design Philosophy

Unlike typical bloated streaming apps, Elsnime embraces a YouTube-style minimal aesthetic:
- **AMOLED-friendly dark mode** as the primary theme, with options for light and automatic system tracking.
- **Custom HLS.js + Plyr video player** featuring customized double-tap gestures to seek, smooth system orientation overrides, and absolute backdrop transparency.
- **Zero-footprint data management**: Everything is stored in local SQLite databases.
- **Aggressive local cache** with TTL-based expiration and smart, scoped cache-invalidation (via pull-to-refresh) to stay lightweight and avoid rate limits.

---

## Features

### Search and Discovery
- **Fuzzy Search**: Instant, query-based search against AniDB browse pages.
- **Smart Disambiguation**: Resolves title overlaps and presents alternative matching choices when necessary.
- **Advanced Metadata Enrichment**: Leverages the Jikan API (MyAnimeList) and AniList GraphQL to present detailed synopses, score tags, genres, cover images, and studio information.
- **Category Chips**: Quickly filter anime by genre with high-performance tag caches.

### High-Performance Playback
- **Inline HLS Player**: Custom built-in media controls wrapping Plyr and HLS.js.
- **Quality Switching**: Select from the best-available HLS variant streams, or let the player auto-adjust based on bandwidth.
- **Custom Gestures**: Double-tap the left/right halves of the player to step forward/backward 10 seconds with a clean seek indicator. Single-tap to play/pause with quick-press cancellation support.
- **Orientation Control**: Enter landscape full-screen seamlessly on rotation (specifically optimized for mobile devices).
- **Proactive Buffering Stop**: Playback, buffering, and background tasks are immediately destroyed when transitioning out of the player screen to prevent unwanted background data usage.

### History and Resume Playback
- **Automatic Watch Tracking**: Saves episode-specific playback position, duration, and thumbnail data locally.
- **Resume Hub**: Quick-access row on the home dashboard to continue exactly where you left off.
- **Persistent Local DB**: Full watch logs stored natively in SQLite, supporting deletion on a per-entry basis.

### Hybrid Caching & Networking
- **Cronet Transport Stack**: Network requests are dispatched using Chrome's native Cronet transport stack rather than standard `HttpURLConnection`, delivering faster connection pooling and robust TLS fingerprint consistency.
- **Cloudflare Auto-Bypassing**: Gracefully detects and handles Cloudflare challenges, instructing users when retry attempts are needed.
- **Bounded Local Cache**: Uses an SQLite-backed cache table capped at 250 entries, utilizing a background LRU (Least Recently Used) cleanup process.
- **Scoped Pull-to-Refresh**: Re-syncs only the relevant components based on the active view (e.g. invalidating only trending chips vs. search indexes), preserving API quotas.

### External Player Support
- **MPV Integration**: Launch streams directly into Termux’s `mpv` CLI binary or the official `mpv-android` application.
- **Header Injection**: Seamlessly passes required browser User-Agents and HTTP Referrer streams to circumvent third-party CDN access blocks.

---

## Quick Start

### Building from Source

To compile and assemble the Android APK, ensure you have the Android SDK installed and configured.

```bash
# Clone the repository
git clone https://github.com/adrianpriza-ai/elsnime.git
cd elsnime

# Build the debug APKs
./gradlew assembleDebug

# Install the debug build to a connected device
./gradlew installDebug
```

Compiled APK artifacts will be output to:
`app/build/outputs/apk/debug/`
- `app-arm64-v8a-debug.apk` (Typical modern devices)
- `app-armeabi-v7a-debug.apk` (Older mobile devices)
- `app-x86_64-debug.apk` / `app-x86-debug.apk` (Android Emulators)

### Manual Installation
Simply transfer the target `.apk` file to your Android phone or tablet, open it with a file manager, and authorize "Install from unknown sources" if prompted by your device settings.

### Downloading Pre-built Releases
Pre-built APKs are available on the [Releases](https://github.com/adrianpriza-ai/elsnime/releases) page. Download the APK matching your device's CPU architecture:

| Architecture | Best For |
|-------------|----------|
| `arm64-v8a` | Most modern Android phones and tablets |
| `armeabi-v7a` | Older 32-bit Android devices |
| `x86_64` | 64-bit Android emulators |
| `x86` | 32-bit Android emulators |

---

## Codebase Structure

A high-level layout of the directories and key files:

```
elsnime/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── assets/             # SPA Frontend
│   │   │   │   ├── css/            # Structural & theme-specific sheets
│   │   │   │   ├── js/             # View routers, core logic, API requests
│   │   │   │   └── ui.html         # Main SPA interface template
│   │   │   ├── java/com/elsnime/   # Native Backend
│   │   │   │   ├── MainActivity.java     # WebView setup, bridge, & DB handlers
│   │   │   │   ├── AniDbScraper.java     # Core scraping engine & metadata
│   │   │   │   └── CronetTransport.java  # Custom high-performance network layer
│   │   │   └── AndroidManifest.xml # Orientation overrides, activities
│   │   └── build.gradle            # Dependencies & Android plugins
└── build.gradle                    # Project build configuration
```

For a thorough look at the JavaScript-to-Java bridges and backend logic, consult [HACKING.md](HACKING.md).

---

## Contributing

Contributions to improve performance, add scrapers, or polish the UI are welcome! Please read our full [Contributing Guidelines](CONTRIBUTING.md) before submitting a pull request.

### Reporting Issues

Found a bug or have a feature request? Please [open an issue](https://github.com/adrianpriza-ai/elsnime/issues/new/choose) using the provided templates. Include your device model, Android version, and steps to reproduce when reporting bugs.

### Submitting Code

1. Fork the project repository.
2. Create your feature branch (`git checkout -b feature/CoolNewFeature`).
3. Commit your changes (`git commit -m 'Add support for CoolNewFeature'`).
4. Push to the branch (`git push origin feature/CoolNewFeature`).
5. Open a Pull Request detailing your changes.

For development guidelines, architecture details, and the JS-to-Java bridge internals, see [HACKING.md](HACKING.md).

---

## Credits

This project would not be possible without the work of the following projects and communities:

| Project | Role in Elsnime |
|-|-|
| [ani-cli](https://github.com/pystardust/ani-cli) | The core scraping logic, AniDB URL resolution flow, and HLS playlist parsing are directly based on this excellent command-line tool by pystardust and its contributors. |
| [AniDB](https://anidb.net/) | Primary anime database used for title search, episode enumeration, and stream URL extraction. |
| [Jikan](https://jikan.moe/) | Unofficial MyAnimeList REST API providing cover art, synopses, scores, and genre metadata. |
| [AniList](https://anilist.co/) | GraphQL API powering trending/popular discovery and supplemental metadata enrichment (genres, episodes, studio info). |
| [Plyr](https://plyr.io/) | Lightweight, accessible, and customizable HTML5 media player built into the in-app player. |
| [HLS.js](https://github.com/video-dev/hls.js/) | JavaScript HTTP Live Streaming client enabling in-browser playback of HLS streams. |
| [Cronet](https://chromium.googlesource.com/chromium/src/+/master/components/cronet/) | Chromium's network stack used for consistent TLS fingerprinting and Cloudflare bypass. |
| [SQLite](https://www.sqlite.org/) | Local relational database powering watch history, app settings, and the bounded TTL cache system. |

If we have inadvertently omitted your project or misattributed any work, please open an issue so we can correct it.

---

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

### Disclaimer
Elsnime is purely a metadata scraper and stream resolver. It does not host, upload, or manage any of the audio/video streams indexed within the application. Please read the full legal policy in [DISCLAIMER.md](DISCLAIMER.md) before using or distributing this software.
