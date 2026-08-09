<div align=center>

![Elsnime](https://banner-img.vercel.app/api/banner?w=500&h=200&r=20&bg=transparent&image=https%253A%252F%252Fadrianpriza-ai.github.io%252Fic_launcher.png%2C20%2C40%2C130%2C130%2C0%2Ctrue&text=Elsnime%2C300%2C110%2C70%2C%23ffffff%2C0%2Cmiddle%2CArial%2Ctrue)

[![Android](https://img.shields.io/badge/Android%205.0%2B%20%7C%20API%2021-3DDC84?style=for-the-badge\&logo=android\&logoColor=white)](https://developer.android.com)
[![Vanilla JavaScript](https://img.shields.io/badge/Vanilla%20JavaScript-F7DF1E?style=for-the-badge\&logo=javascript\&logoColor=black)](app/src/main/assets/ui.html)
[![Java / SQLite](https://img.shields.io/badge/Java%20%2F%20SQLite-white?style=for-the-badge\&logo=openjdk\&logoColor=black)](app/src/main/java/com/elsnime)
[![GPL-3.0](https://img.shields.io/badge/GPL--3.0-white?style=for-the-badge\&logo=gnu\&logoColor=red)](LICENSE)

</div>

Elsnime is an anime streaming client for Android. It wraps a responsive vanilla CSS/JS frontend inside a WebView, backed by a native Java scraping engine and an SQLite data layer.

There are no user accounts, no telemetry, no tracking, and no intrusive permission requests.

Elsnime keeps a minimal dark UI:
- Plyr player with AniSkip-powered skip buttons for intros, recaps, and credits.
- All data stored locally in SQLite databases.
- Aggressive cache with TTL expiration and scoped invalidation (via pull-to-refresh) to respect API rate limits.

---

## Features

### Search and Discovery

- **Fuzzy Search**: Instant, query-based search against AniDB browse pages.
- **Smart Disambiguation**: Resolves title overlaps and offers alternative matches.
- **Metadata Enrichment**: Pulls synopses, score tags, genres, cover images, and studio info from the Jikan API (MyAnimeList) and AniList GraphQL.
- **Category Chips**: Filter anime by genre with cached tag lists.

### Playback

- **Inline HLS Player**: Plyr with hls.js for adaptive HLS playback.
- **Quality Switching**: Choose from available HLS variants, or let the player adjust to bandwidth.
- **Player UI**: Tapping the video never pauses playback; controls are driven entirely by the Plyr control bar (play, progress, quality & speed menus, fullscreen).
- **Fullscreen**: The fullscreen button fills the player across the whole screen inside the WebView (Plyr fallback mode), keeping the control bar overlaid, hiding the status/navigation bars, and rotating to landscape. Leaving fullscreen restores the bars and orientation; outside fullscreen the system bars stay visible.
- **Skip Intros & Recaps**: Community-sourced skip times from [AniSkip](https://api.aniskip.com/) show a "Skip Intro / Recap / Credits" button during openings or endings; tap to jump past them. Disable in Settings.
- **Background Buffering Stops**: Playback, buffering, and background tasks halt immediately when leaving the player screen.

### History and Resume Playback

- Saves episode-specific playback position, duration, and thumbnail data locally.
- Quick-access row on the home dashboard to continue where you left off.
- Reopening an episode resumes from the saved position (with a "Resuming from m:ss" notice); episodes already marked as watched start from the beginning.
- Full watch logs stored in SQLite; delete per entry.

### Networking

- **Cronet transport**: Uses Chrome's native Cronet stack instead of `HttpURLConnection`, for faster connection pooling and consistent TLS fingerprinting.
- **Cloudflare bypass**: Detects Cloudflare challenges and prompts users to retry.
- **Bounded Local Cache**: SQLite-backed cache table capped at 250 entries, with background LRU cleanup.
- **Scoped Pull-to-Refresh**: Re-syncs only the active view (e.g. trending chips, not search indexes).

### External Player Support

- **MPV Integration**: Launch streams into Termux's `mpv` CLI or the official `mpv-android` app.
- **Header Injection**: Passes browser User-Agent and Referrer headers to bypass third-party CDN blocks.

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

Compiled APKs land in:
`app/build/outputs/apk/debug/`
- `app-arm64-v8a-debug.apk` (Typical modern devices)
- `app-armeabi-v7a-debug.apk` (Older mobile devices)
- `app-x86_64-debug.apk` / `app-x86-debug.apk` (Android Emulators)

### Manual Installation

Transfer the target `.apk` file to your Android phone or tablet, open it with a file manager, and authorize "Install from unknown sources" if prompted by your device settings.

### Downloading Pre-built Releases

Pre-built APKs are available on the [Releases](https://github.com/adrianpriza-ai/elsnime/releases) page. Download the APK matching your device's CPU architecture:

| Architecture | Best For |
|-|-|
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

Contributions to improve performance, add scrapers, or polish the UI are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines before submitting a pull request.

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

| Project | Role in Elsnime |
|-|-|
| [ani-cli](https://github.com/pystardust/ani-cli) | Core scraping logic, AniDB URL resolution, and HLS playlist parsing. |
| [AniDB](https://anidb.net/) | Primary anime database used for title search, episode enumeration, and stream URL extraction. |
| [Jikan](https://jikan.moe/) | Unofficial MyAnimeList REST API providing cover art, synopses, scores, and genre metadata. |
| [AniList](https://anilist.co/) | GraphQL API powering trending/popular discovery and supplemental metadata enrichment (genres, episodes, studio info). |
| [Plyr](https://plyr.io/) | Open-source HTML5 media player driving the in-app player UI. |
| [hls.js](https://github.com/video-dev/hls.js/) | HLS (m3u8) playback engine powering adaptive streaming in the player. |
| [AniSkip](https://api.aniskip.com/) | Community-sourced opening, recap, and ending skip times powering the player's skip buttons. |
| [Cronet](https://chromium.googlesource.com/chromium/src/+/master/components/cronet/) | Chromium's network stack used for consistent TLS fingerprinting and Cloudflare bypass. |
| [SQLite](https://www.sqlite.org/) | Local relational database powering watch history, app settings, and the bounded TTL cache system. |

If any project or contributor was omitted or misattributed, please open an issue.

---

## License

This project is licensed under the GPL-3.0 License - see the [LICENSE](LICENSE) file for details.

**Author:** Adrian Priza Wijaya — [coreygit1@gmail.com](mailto:coreygit1@gmail.com)

### Disclaimer

Elsnime is purely a metadata scraper and stream resolver. It does not host, upload, or manage any of the audio/video streams indexed within the application. Adrian Priza Wijaya is responsible only for the Elsnime application itself and not for any third-party services, APIs, or streaming platforms it interacts with. Please read the full legal policy in [DISCLAIMER.md](DISCLAIMER.md) before using or distributing this software.
