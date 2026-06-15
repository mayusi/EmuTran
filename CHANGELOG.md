# Changelog

All notable changes to EmuTran are documented here.

## v0.3.0

### Added
- **Setup Profile backup & restore** — export your current setup (storage root, picked emulators, dual-screen and GPU-driver flags) to a small JSON file, then import it on a new device to skip the setup flow. Found at the new Profile screen.
- **BIOS file validation in Health Check** — the Health screen now inspects actual BIOS files (not just folders), reports which files are missing, and checks hashes against publicly documented reference values. Only systems the user plausibly configured are checked to avoid false-positive warnings.
- **Catalog source check in Health Check** — a new Health check probes a sample of emulator entries against their download sources, catching dead links early before a setup run hits them.
- **Per-emulator GPU driver hints on Dashboard** — Adreno-device users see a one-line hint on each emulator card indicating the recommended driver.
- **Catalog "what's new" banner** — a dismissible banner on the Dashboard surfaces newly added or updated emulator entries after a manifest refresh.
- **Show/hide toggle on GitHub token field** — the token entry in the About screen is now masked by default with a visibility toggle, matching standard password-field conventions.

### Changed
- Package-manager scan on Dashboard and Picker screens moved fully off the main thread; `emuHelperInstalled` is now a `StateFlow` rather than a blocking PM call in composition.
- Rapid successive refresh calls (e.g. after Update All completes) are debounced so only one reload fires instead of one per completed update.
- Update check writes all results in a single DataStore transaction instead of one per entry, eliminating redundant re-decode on every write.
- Health Check checks now run with bounded concurrency (semaphore-guarded `async`/`awaitAll`) for faster overall completion.
- Foreground service uses `SupervisorJob`; tapping the progress notification navigates back to the app.
- Cancelling a setup run now immediately stops the foreground service and dismisses the notification (previously the notification could linger).
- The Test Install debug screen has been removed.
- GitHub token store uses an eagerly-started `StateFlow` so the first API call on launch is authenticated even before any subscriber exists.
- Picker card toggle switched from `clickable` to `toggleable` with `Role.Checkbox`; GPU driver opt-in card on the Shizuku screen similarly updated for correct TalkBack semantics.
- Hardened internals and expanded test coverage (SHA-256 sidecar parsing, manifest diff store, health checks, update paths).

### Fixed
- **Profile Import hang** — importing a profile no longer freezes the UI; file reading is dispatched to IO, and the ViewModel guards against re-entrant invocations.
- **Android 10 crash** — `AllFilesAccess` now gates `Environment.isExternalStorageManager()` and `ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION` behind an API 30 check; on Android 10 (API 29) it falls back to `WRITE_EXTERNAL_STORAGE`, fixing a `NoSuchMethodError` / `ActivityNotFoundException` crash at the permissions screen.
- Health Check Back button now shows the D-pad focus ring when navigated to with a controller.
- Picker app list sorted inside `remember` so the sort does not re-run on every recomposition; installed-count also memoised.
- `confirmOffline` no longer tries to complete an already-cancelled deferred, which could silently swallow the cancel.
- Uninstall flow no longer triggers a full PM scan; uses the cached registry snapshot instead.
- `updateAll()` is now mutex-guarded, preventing a double-tap from launching two concurrent update sequences.

### Security
- **Update All / Update One now verify APK integrity.** The dashboard update path previously passed `null` as the expected SHA-256, installing downloaded APKs without an integrity check. It now fetches the SHA-256 sidecar (when published by the source) and aborts with an error if the fetch fails — matching the behaviour already enforced on the initial setup path.
- Profile import caps file reads at 256 KB and validates the imported `storageRoot` against the device's real mounted volumes before prompting the user to adopt it, blocking path-traversal and sandbox-escape attempts via a crafted profile file.
- Profile import schema-version gate rejects files produced by a newer version of EmuTran rather than silently mis-applying unknown fields.

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
