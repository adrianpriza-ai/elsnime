# Contributing to Elsnime

Read [HACKING.md](HACKING.md) first for an overview of the architecture and database design. Be respectful and collaborative.

## Setup

You need JDK 17+ and the Android SDK. Node.js is optional for checking frontend JavaScript.

```bash
git clone https://github.com/adrianpriza-ai/elsnime.git
cd elsnime
./gradlew help
```

## Development Guidelines

### Frontend

Frontend files live in `app/src/main/assets/`.

- Use vanilla JavaScript and CSS; avoid large dependencies.
- Match the existing dark, minimal design.
- Preserve keyboard focus, hover states, and accessibility.
- Keep layouts responsive across screen sizes and orientations.

### Backend

Java source lives in `app/src/main/java/com/elsnime/`.

- Run network, scraping, database, and download work on `backend.executor`; never block the UI thread.
- Return failures as JSON with an `"error"` key instead of crashing.
- Use the existing cache helpers and suitable TTL constants.
- Avoid unnecessary Android permissions; prefer MediaStore and scoped storage.

## Reporting Issues

[Open an issue](https://github.com/adrianpriza-ai/elsnime/issues/new/choose) with:

- Device model, Android/API version, and app version or commit.
- Clear reproduction steps and expected versus actual behavior.
- Relevant screenshots or `adb logcat` output.

Feature requests should explain the use case and any alternatives considered.

## Pull Requests

1. Create a descriptive branch, such as `feature/search-highlights` or `fix/player-crash`.
2. Keep changes focused and match the surrounding code style.
3. Validate your work:

   ```bash
   ./gradlew assembleDebug
   node --check app/src/main/assets/js/<changed-file>.js  # for JS changes
   ```

4. Use a clear, imperative commit message, such as `Fix player gesture boundaries`.
5. Open a PR against `main` describing what changed and how to verify it.
