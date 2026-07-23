# Cachelio

**Cachelio** by NullMargin is an Android browser that helps you find videos while you browse, download them for offline use, and watch them privately on your device.

## What you can do

- **Browse the web** in a built-in browser with a local start page (Baidu, Bing, and Google)
- **Detect videos automatically** as pages play or load media
- **Download for offline use** — regular video files and HLS/M3U8 streams, with resume if a download is interrupted
- **Manage your library** in Files — search, sort, share, and group downloads
- **Watch in the app** with an in-app player and optional auto-play next

## Privacy design

Cachelio keeps your private library under your control on this device.

- **Hide media** in Files so it stays out of the normal library view
- **Privacy password** (optional) protects Privacy settings and unlocking the current session
- **On-device only** — your password verifier is stored only on this device. There is no cloud account, password reset, or recovery. If you forget it, you must clear the app data
- **Session unlock** — lasts for the current app session only and locks again when the app process restarts. While unlocked, hidden files are shown, and bookmarks added while unlocked are only visible until you unlock again. Bookmark toolbar actions stay available when locked; only private bookmark entries are hidden

## Get the app

Official releases are published on [GitHub Releases](https://github.com/NullMargin/z-grab-browser3/releases) as `cachelio-<tag>.apk` (for example `cachelio-v2.0.3.apk`).

For development builds, install the latest debug APK from the GitHub Actions artifact `cachelio-debug-apk-<branch>` (workflow: [Cachelio Debug APK](.github/workflows/cachelio-debug-apk.yml)). Pushes to any branch build that branch.

Cachelio uses package id `com.holeintimes.vbrowser`, so it installs as an update over previous VBrowser builds and keeps your existing downloads and settings.

## Support

Questions or feedback: [3dzguy@outlook.com](mailto:3dzguy@outlook.com) (also listed in **About** in the app).

## For developers

Requires **JDK 17+**, Android SDK (**compileSdk 35**), and an environment that can run Android `aapt2` (typically x86_64 Linux, macOS, or Windows via Android Studio).

```bash
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
./gradlew :app:assembleDebug
./gradlew :app:installDebug
```

Or open the project in Android Studio (Ladybug+) and sync.

CI: [`.github/workflows/cachelio-debug-apk.yml`](.github/workflows/cachelio-debug-apk.yml) builds `assembleDebug` on push (any branch), PR, and `workflow_dispatch`, and uploads `cachelio-debug-apk-<branch>`. [`.github/workflows/cachelio-release-apk.yml`](.github/workflows/cachelio-release-apk.yml) builds a signed `assembleRelease` APK and attaches it when a GitHub Release is published.

Reference PRD: [`low-code/docs/BUSINESS_REQUIREMENTS.md`](low-code/docs/BUSINESS_REQUIREMENTS.md)
