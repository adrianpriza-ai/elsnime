<div align=center>

![Elsnime](https://banner-img.vercel.app/api/banner?w=500&h=200&r=20&bg=transparent&image=https%253A%252F%252Fadrianpriza-ai.github.io%252Fic_launcher.png%2C20%2C40%2C130%2C130%2C0%2Ctrue&text=Elsnime%2C300%2C110%2C70%2C%23ffffff%2C0%2Cmiddle%2CArial%2Ctrue)

[![Android](https://img.shields.io/badge/Android%206.0%2B%20%7C%20API%2023-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://developer.android.com)
[![JavaScript](https://img.shields.io/badge/JavaScript-F7DF1E?style=for-the-badge&logo=javascript&logoColor=black)](app/src/main/assets/ui.html)
[![Java](https://img.shields.io/badge/Java-white?style=for-the-badge&logo=openjdk&logoColor=black)](app/src/main/java/com/elsnime)
[![SQLite](https://img.shields.io/badge/SQLite-white?style=for-the-badge&logo=sqlite&logoColor=%2344A8E0)](app/src/main/java/com/elsnime)
[![GPL-3.0](https://img.shields.io/badge/GPL--3.0-white?style=for-the-badge&logo=gnu&logoColor=red)](LICENSE)

</div>

Elsnime is an anime streaming client for Android. A vanilla CSS/JS frontend runs in a WebView, backed by native Java scrapers and SQLite. No accounts, no telemetry, no tracking, and no intrusive permission requests.

- Plyr player with AniSkip-powered skip buttons for intros, recaps, and credits.
- Native episode & full-series downloads saved to `Movies/Elsnime/` as MP4.
- Offline-capable player: Plyr, hls.js, and the UI font ship inside the APK — no CDN at load time.
- All data stored locally in SQLite, with TTL caching and scoped refresh to respect API rate limits.

---

## Features

### Search and Discovery

- **Fuzzy Search**: Instant query-based search against AniDB browse pages, with smart title disambiguation.
- **Metadata Enrichment**: Synopses, scores, genres, covers, and studio info from Jikan (MyAnimeList) and AniList GraphQL.
- **Category Chips**: Filter anime by genre with cached tag lists.

### Playback

- **Inline HLS Player**: Plyr with hls.js for adaptive HLS playback, both vendored in the APK so the player boots offline.
- **Quality Switching**: Pick from available HLS variants or let the player adapt to bandwidth; the starting quality comes from Settings.
- **Player UI**: No tap-to-pause; playback is driven entirely by the Plyr control bar (play, progress, quality & speed menus, fullscreen).
- **Fullscreen**: Fills the screen inside the WebView, overlaying the control bar, hiding the system bars, and rotating to landscape; leaving restores everything.
- **Skip Intros & Recaps**: Community-sourced skip times from [AniSkip](https://api.aniskip.com/) show a "Skip Intro / Recap / Credits" button during openings or endings (disable in Settings).
- **Clean CDN Errors**: Blocked or expired streams report their HTTP status (e.g. "The CDN blocked this stream (HTTP 403)") with a pointer to MPV.

### Downloads

- **Per-Episode & Full-Series**: Queue a single episode or the whole series with one "Download All" tap.
- **MP4 Output**: HLS MPEG-TS segments are stitched and remuxed to `.mp4` in-app with the **media3 muxer** (a pure-Java writer) — the system `MediaMuxer` is never used (its native writer crashes some devices), and anything that can't be muxed is kept as raw `.ts`; fMP4 streams (already MP4) are saved as `.mp4`.
- **Honors Default Quality**: Picks the closest rendition from the master playlist. Settings can split streaming and downloads into separate qualities ("Separate stream & download quality"); for downloads, "Best" grabs the highest rendition.
- **Downloads Tab**: Live progress (percent, bytes, segment count), cancel, retry, delete — persisted across restarts and reconciled with disk.
- **Background Downloads**: A foreground service keeps the queue running when the app is backgrounded or swiped away from recents, with a persistent progress notification (episode title, percent + bytes, a Cancel action) that updates in real time and clears when the queue finishes.
- **Resume After Interruption**: Downloads survive a force-stop or process death — progress is written to disk after every segment, and the next launch resumes from the last completed segment (or re-runs the MP4 conversion when all segments were already fetched).
- **Play in-App**: A Play button on each download streams the file through a loopback HTTP server into the built-in player (`.mp4` direct, `.ts` via hls.js) — no external app needed. Played downloads record progress in watch history, resume from where you left off, and open in MPV like any streamed episode.
- **Permission-Free on Android 10+**: Files land via MediaStore; Android 6–9 ask for legacy storage access once. On Android 13+ the app requests notification permission once so download progress can show in the shade (denying only hides the notification — downloads still work).

### History and Resume Playback

- Saves per-episode position, duration, and thumbnail locally.
- Continue-watching row on the home dashboard.
- Reopening an episode resumes from the saved position (with a "Resuming from m:ss" notice); watched episodes start from the beginning.
- Downloaded playback counts as history: positions and resume points save under the series, same as streaming.
- Full watch logs in SQLite; delete per entry.

### Networking

- **Cronet transport**: Chrome's native Cronet stack instead of `HttpURLConnection` — faster connection pooling and consistent TLS fingerprinting.
- **Cloudflare bypass**: Detects Cloudflare challenges and prompts users to retry.
- **Bounded Local Cache**: SQLite-backed cache capped at 250 entries with background LRU cleanup.
- **Scoped Pull-to-Refresh**: Re-syncs only the active view (e.g. trending chips, not search indexes).

### External Player Support

- **MPV Integration**: Launch streams into the official **mpv-android** app (URL + title via `ACTION_VIEW` intent), with a Termux `mpv` CLI fallback on rooted setups.
- **Downloaded Files in MPV**: The Open in MPV button also works while a download is playing — the file is handed to mpv with its container type (`video/mp4` or `video/mp2t`), no stream lookup involved.
- **Header Injection**: The browser User-Agent and Referrer pass to mpv through its config-include file so CDN-gated streams load (set the `include=...` line once in mpv.conf).

---

## Quick Start

### Building from Source

Requires the Android SDK.

```bash
git clone https://github.com/adrianpriza-ai/elsnime.git
cd elsnime
./gradlew assembleDebug   # build debug APKs
./gradlew installDebug    # install to a connected device
```

Compiled APKs land in `app/build/outputs/apk/debug/`:
- `app-arm64-v8a-debug.apk` (modern devices)
- `app-armeabi-v7a-debug.apk` (older 32-bit devices)
- `app-x86_64-debug.apk` / `app-x86-debug.apk` (emulators)

### Manual Installation

Transfer the `.apk` to your device, open it with a file manager, and authorize "Install from unknown sources" if prompted.

### Pre-built Releases

Pre-built APKs are on the [Releases](https://github.com/adrianpriza-ai/elsnime/releases) page. Pick the APK matching your device's CPU architecture:

| Architecture | Best For |
|-|-|
| `arm64-v8a` | Most modern Android phones and tablets |
| `armeabi-v7a` | Older 32-bit Android devices |
| `x86_64` | 64-bit Android emulators |
| `x86` | 32-bit Android emulators |

---

## Contributing

Contributions to improve performance, add scrapers, or polish the UI are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md) before submitting a pull request, and [HACKING.md](HACKING.md) for architecture and internals.

Found a bug or have a feature request? [Open an issue](https://github.com/adrianpriza-ai/elsnime/issues/new/choose) using the provided templates, including your device model, Android version, and steps to reproduce.

---

## Credits

| Project | Role in Elsnime |
|-|-|
| [ani-cli](https://github.com/pystardust/ani-cli) | Core scraping logic, AniDB URL resolution, and HLS playlist parsing. |
| [AniDB](https://anidb.net/) | Primary anime database used for title search, episode enumeration, and stream URL extraction. |
| [Jikan](https://jikan.moe/) | Unofficial MyAnimeList REST API providing cover art, synopses, scores, and genre metadata. |
| [AniList](https://anilist.co/) | GraphQL API powering trending/popular discovery and supplemental metadata enrichment (genres, episodes, studio info). |
| [Plyr](https://plyr.io/) | Open-source HTML5 media player driving the in-app player UI (vendored locally). |
| [hls.js](https://github.com/video-dev/hls.js/) | HLS (m3u8) playback engine powering adaptive streaming in the player (vendored locally). |
| [Media3](https://github.com/androidx/media) | Google's open-source (Apache-2.0) media library; its pure-Java `media3-muxer` remuxes downloaded `.ts` streams to `.mp4` — the system `MediaMuxer` is never used (its native writer crashes some devices). |
| [mpv-android](https://github.com/mpv-android/mpv-android) | Optional external player launched via intent for CDN-gated streams. |
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
