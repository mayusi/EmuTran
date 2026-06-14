# Changelog

All notable changes to EmuTran are documented here.

## v0.2.0

### Added
- Full controller / D-pad navigation across every screen — no touchscreen required.
- In-app self-update: background version check, patch notes, SHA-256-verified download, system-installer hand-off.
- Emulator update detection with a dashboard badge and one-tap Update All, plus optional update notifications.
- Setup Health Check — verifies the Emulation tree, BIOS folders, installed emulators, and staged drivers.
- Offline pre-flight warning before a setup run with no network.
- SD-card / storage-volume picker for the Emulation folder location.
- Quick Setup — one tap to install the recommended emulator set.
- Optional GitHub token to lift the API rate limit during large installs.

### Changed
- Reworked UI: monochrome design system, redesigned emulator cards, step indicator across setup, Markdown-rendered patch notes.
- Resilient downloads: retry with backoff, HTTP range-resume, foreground service with progress.
- Manifest auto-refresh from GitHub with ETag caching.

### Fixed
- Dashboard layout overlap, a stale update badge, a GPU-detection race, and a driver-staging path bug.
- Throttled download progress and moved package scans off the main thread for smoother performance on mid-range handhelds.

### Security
- APK integrity verification against a SHA-256 sidecar before install.
- Filename sanitization (path-traversal protection) and HTTPS-downgrade blocking on downloads.
- Environment-variable signing; backups disabled; no telemetry.

## v0.1.1

- Initial public release: guided setup flow, Shizuku install, Emulation folder scaffold, and Adreno driver staging.
