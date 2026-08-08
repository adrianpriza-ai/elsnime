# Contributing to Elsnime

Thank you for your interest in contributing to Elsnime! This document provides guidelines, workflows, and standards to help you get your improvements merged quickly.

Before contributing, please make sure to read our [HACKING.md](HACKING.md) to understand the project's hybrid architecture and database designs.

---

## ode of Conduct

By participating in this project, you agree to maintain a respectful, welcoming, and collaborative environment. Please focus on constructive discussions and high-quality, readable code.

---

## etting Started

### 1. Prerequisites
- **Java Development Kit (JDK)**: Version 17 or higher.
- **Android SDK**: Command Line Tools or Android Studio.
- **Node.js (Optional)**: If you plan on running automated formatting or local linters on the asset folder.
- **Python 3**: For running the local web-only server implementation (`app.py`) during UI design.

### 2. Setting Up Your Workspace
Clone the repository and verify the Gradle environment by requesting a help build:

```bash
git clone https://github.com/adrianpriza-ai/elsnime.git
cd elsnime
./gradlew help
```

---

## UI & Frontend Guidelines

All frontend assets are located in `app/src/main/assets/`.

- **Keep it Lightweight**: Write clean, modern vanilla JavaScript and CSS variables. Do not introduce large, external dependencies (e.g., React, Vue, jQuery) into the asset folders.
- **YouTube-Style Aesthetic**: Maintain a minimal, highly legible visual style. Use black (`#0f0f0f`) or very dark gray for backgrounds, light gray (`#f1f1f1`) for text, and standard gray colors for borders and lines.
- **Accessibility (a11y)**:
  - Keep active buttons clickable with clear hover boundaries (`--bg-hover` is standard).
  - Use appropriate focus state properties. For example, modal overlays and picker sheets should restore focus to the last-active elements when dismissed.
- **Cross-Device Performance**: Test views on multiple layout widths. The app automatically flips to fullscreen landscape on mobile devices, but uses static buttons on tablets or desktop wrappers. Ensure your layouts are fully responsive.

---

## Backend & Java Guidelines

The backend source is located in `app/src/main/java/com/elsnime/`.

- **Asynchronous Execution**: All scraping (`AniDbScraper`), network queries (`CronetTransport`), and database operations (`HistoryDb`) MUST run inside background execution threads using the cached thread pool (`backend.executor.execute(...)`). Never block the primary UI Thread.
- **Error Propagation**: Gracefully handle parser changes or networking failures. Never let scraping errors bubble up and crash the application; return JSON response structures carrying an `"error"` message key, allowing the frontend toast systems to gracefully report them.
- **Cache Compliance**: When making new queries, check if the data can be cached using `cachedArray` or `cachedObject` helpers. Avoid hardcoding standard values; use TTL values based on how frequently the source updates (e.g., `TTL_DAY` for search indexing, `TTL_HOUR` for episodic scrapers).
- **No Intrusive Permissions**: We maintain a strict privacy-conscious model. Do not introduce features that require invasive device access, location tracking, contacts, or storage write/read permissions.

---

## Reporting Issues

If you encounter a bug, crash, or unexpected behavior, please [open an issue](https://github.com/adrianpriza-ai/elsnime/issues/new/choose) using the appropriate template:

- **Bug Report**: For crashes, playback failures, UI glitches, or broken scrapers.
- **Feature Request**: For suggesting new features or improvements.

### What to Include in a Bug Report
To help us resolve issues quickly, please provide as much of the following as possible:

1. **Device & OS**: Android version (e.g., `14 / API 34`) and device model (e.g., `Pixel 7`).
2. **App Version**: The version string or commit hash you are running.
3. **Steps to Reproduce**: A numbered list of actions that reliably trigger the issue.
4. **Expected vs. Actual Behavior**: What you expected to happen versus what actually occurred.
5. **Screenshots or Logs**: Attach screenshots or paste relevant `adb logcat` output.

### What to Include in a Feature Request
- A clear description of the feature and its use case.
- Any alternative approaches you have considered.
- Mockups or examples, if applicable.

---

## Submission Process

### 1. Create a Branch
Create a descriptive branch from the main branch:
```bash
git checkout -b feature/ImprovedSearchHighlights
# OR
git checkout -b fix/MpvIntentCrash
```

### 2. Code Quality Check
Before submitting:
- Run a compile pass locally to ensure there are no compilation errors:
  ```bash
  ./gradlew assembleDebug
  ```
- Keep code clean, matching the indentation styles of the surrounding codebase.

### 3. Commit Your Changes
Keep commit messages concise and informative. Write them in the imperative mood:
```
Add: Support for subtitle language preferences in MPV intent launcher
Fix: Double-tap gestures ignoring slider boundaries on the player controls
```

### 4. Open a Pull Request (PR)
- Push your branch to your fork.
- Submit a Pull Request targeting our main branch.
- Clearly describe what the PR accomplishes, which files are modified, and steps to manually verify or test the changes.
