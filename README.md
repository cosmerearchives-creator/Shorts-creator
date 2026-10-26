# Shorts Creator

Lightweight, offline-first Android editor for short-form video.

## Planned features
- Kotlin / Jetpack Compose
- Full multitrack timeline: magnetic main video track and freely positioned overlays
- Canvas presets: 9:16 (default), 16:9, 1:1, 4:5
- Adaptive previews and optional proxy generation; export using original media
- Non-destructive local projects
- Background exports and proxy jobs with progress notifications and battery safeguards

## Updates — required from v0.1
We will distribute signed APKs through GitHub Releases. The app will check the public release feed for a newer `versionCode`, display release notes, and offer a download/install action using Android's standard installation confirmation.

Release requirements:
- Stable application ID and the same signing key for all upgrades.
- A release signing key securely backed up; credentials in GitHub Actions secrets, never committed.
- Verify the update source and downloaded APK; allow users to decline or defer updates.
- Preserve projects and source media; do not interrupt an active export.
- Keep release downloads public unless an authenticated distribution mechanism is added.

**Status:** Project setup / architectural planning. No usable APK or update implementation has been released yet.
