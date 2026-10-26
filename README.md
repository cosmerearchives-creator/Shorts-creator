# Shorts Creator

Lightweight, offline-first Android video editor, following the Silverlight design family.

## V0.3 development alpha
- Create local projects (9:16 default; 16:9, 1:1, 4:5)
- Import media from the Android document picker
- Play imported videos and display a basic two-lane timeline
- Save media references and project details locally
- Settings page with GitHub Releases update check
- Automatic GitHub Actions debug APK build

**V0.2 introduced:** persisted in/out trims for imported video and single selected-clip MP4 export to Movies/ShortsCreator. **Not yet implemented:** splitting, timeline dragging, multilayer compositing, multi-clip export, background export/proxy jobs, verified APK installation, or signed release publishing. Refer to [build/update notes](docs/build-and-updates.md).

## Planned roadmap
Magnetic main video track, freely positioned overlays and audio, non-destructive edits, Media3 exporting from original sources, adaptive preview/proxies, background processing and stable signed releases.

## Theme
Use the user-provided Silverlight Archives screenshot; see [visual theme](docs/visual-theme.md).

## V0.3 update workflow
- Checks published tagged GitHub alpha releases at startup and from Settings.
- Release workflow publishes `versionCode: N` in release notes, with a signed APK.
- In-app download, progress and installer handoff; checks package name, version, size, and matching signer.
- Android may require the user to allow installing unknown apps for Shorts Creator and approve installation.
- Published GitHub release workflow must be run manually from GitHub Actions; the regular push workflow only produces debug artifacts.
- V0.2 must be upgraded manually or through its old release page; in-app installer works beginning in V0.3.
