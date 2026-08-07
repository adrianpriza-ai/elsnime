# Elsnime

A minimal Android anime streaming app. Search AniDB, stream episodes in-app with HLS, and track your watch history.

The scraping logic is based on [ani-cli](https://github.com/pystardust/ani-cli) by pystardust.

## About

Elsnime is a lightweight, privacy-conscious anime streaming client for Android. It is built as a WebView-based single-page application, combining the flexibility of web technologies with the performance of native Android code.

The app focuses on simplicity and efficiency. There are no accounts, no tracking, and no unnecessary permissions. Just search for anime, stream episodes, and track what you have watched.

## Features

### Search and Discovery
- Search anime by title with instant results from AniDB
- Filter search results by genre using intuitive genre chips
- Browse trending and popular anime from AniList
- View detailed anime information including synopsis, episode count, and air dates

### Video Playback
- Built-in HLS video player powered by HLS.js and Plyr
- Automatic quality selection based on available streams
- Manual quality switching for bandwidth control
- Double-tap to seek (10 seconds forward/back)
- Custom fullscreen with auto-rotate support
- Playback progress tracking with resume functionality

### Watch History
- Automatically track watched episodes and progress
- Continue watching section on home screen
- Full history view with episode-level details
- Progress indicators showing how far you are in each episode
- Clear history option in settings

### User Interface
- Clean, minimal YouTube-inspired design
- Dark theme by default with Light and Auto options
- Smooth animations and transitions
- Responsive layout for phones and tablets
- Pull-to-refresh to reload content without full cache clear

### External Playback
- Optional integration with MPV media player
- Supports both MPV CLI and mpv-android app
- Pass referrer and user-agent headers for stream access
- Seamless switching between web and MPV playback

### Caching System
- SQLite-based cache with 250 entry limit
- TTL-based expiration (24 hours for most data)
- Scoped cache clearing (clear only what you need)
- Automatic cache maintenance in background

## Quick Start

### Building from Source

```bash
# Clone the repository
git clone https://github.com/adrianpriza-ai/elsnime.git
cd elsnime

# Build debug APK
./gradlew assembleDebug

# Install to connected device
./gradlew installDebug
```

The APKs will be generated at `app/build/outputs/apk/debug/`:
- `app-arm64-v8a-debug.apk`
- `app-armeabi-v7a-debug.apk`
- `app-x86_64-debug.apk`
- `app-x86-debug.apk`

### Installing the APK

Transfer the APK to your Android device and open it to install. You may need to enable "Install from unknown sources" in your device settings.

### Key Components

**MainActivity.java**
- Initializes the WebView and configures JavaScript bridge
- Handles Android back button navigation
- Manages theme changes and orientation
- Exposes native APIs to JavaScript (systemTheme, setOrientation, etc.)

**AniDbScraper.java**
- Scrapes anime data from AniDB browse pages
- Fetches episode lists and stream URLs
- Integrates with Jikan (MyAnimeList) and AniList (GraphQL) for metadata
- Implements caching with TTL and prefix-based clearing

**CronetTransport.java**
- HTTP transport layer using Chrome's Cronet library
- Provides better Cloudflare bypass than standard HttpURLConnection
- Consistent TLS fingerprint across requests

**ui.html**
- Single-page application structure
- Views for Home, Search, Detail, Player, History, and Settings
- Dynamic content rendering without page reloads

## Technology Stack

| Layer | Technology |
|-------|------------|
| Platform | Android 5.0+ (API 21) |
| Backend | Java Android with SQLite |
| Frontend | Vanilla JavaScript SPA |
| Video Player | Plyr + HLS.js |
| Data Sources | AniDB, Jikan MyAnimeList API, AniList GraphQL |
| Network | Cronet (Chrome's HTTP stack) |
| Cache | SQLite (250 entries, TTL-based) |
| Styling | CSS with CSS variables for theming |

## Requirements

### Minimum Requirements
- Android 5.0 Lollipop (API 21) or higher
- Internet connection
- WebView support (standard on all modern Android devices)

### Optional Dependencies
- MPV CLI binary in PATH (for terminal-based MPV playback)
- mpv-android app (com.is.mpv.android) for app-based MPV playback

### Permissions
- `android.permission.INTERNET` - Required for network access

## Configuration

### Theme Settings
The app supports three theme modes:
- **Dark** - YouTube-style dark theme (default)
- **Light** - Light background with dark text
- **Auto** - Follows system dark mode setting

### Player Settings
- **Default Player** - Choose between Web (built-in) or MPV
- **Default Language** - Prefer SUB (subtitled) or DUB (English dub)

### Cache Management
- Cache entries expire after 24 hours by default
- Maximum 250 entries stored
- Pull-to-refresh clears only relevant cache entries
- Settings allow full cache wipe if needed

## Contributing

Contributions are welcome! Here are some ways you can help:

- Report bugs and issues
- Suggest new features
- Submit pull requests
- Improve documentation
- Translate the app

## License

MIT License - see [LICENSE](LICENSE) for details.

## Credits

- [ani-cli](https://github.com/pystardust/ani-cli) - The scraping logic is based on this excellent CLI tool
- [AniDB](https://anidb.net/) - Primary anime data source with extensive database
- [Jikan](https://jikan.moe/) - Unofficial MyAnimeList API wrapper
- [AniList](https://anilist.co/) - Modern anime metadata with GraphQL API
- [Plyr](https://plyr.io/) - Beautiful and accessible video player
- [HLS.js](https://github.com/video-dev/hls.js/) - JavaScript HLS implementation
- [Cronet](https://chromium.googlesource.com/chromium/src/+/master/components/cronet/) - Chrome's network stack

## Disclaimer

This app scrapes data from AniDB and streams content from third-party sources. The developers are not responsible for how users choose to use this application. Please support the anime industry by purchasing official merchandise, Blu-rays, or using legitimate streaming services when possible.

Use responsibly and respect content creators' rights.