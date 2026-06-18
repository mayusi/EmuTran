# Changelog

All notable changes to EmuTran are documented here.

## v0.3.4

### Added
- **BIOS Helper.** A new guided screen (from the Dashboard) that shows, per system, exactly which BIOS files you still need, the folder to drop them in, and whether the files you've placed are verified-good against known reference hashes — so you can tell a good dump from a bad one at a glance. EmuTran never provides BIOS files; this only inspects your own.
- **ROM sorter.** A new tool that scans your device for game files you've already copied over (Downloads, SD card, anywhere) and offers to move them into the correct `Emulation/roms/<system>/` folders by type — with a "needs your choice" bucket for ambiguous formats. Every move is explicit and confirmed; files are never deleted unless the move fully succeeds, and nothing is ever downloaded.
- **Resilient downloads.** When an emulator's latest release is unavailable (a dead or moved source), EmuTran now automatically falls back to an older release from the same trusted source before giving up, and tells you when it did — so a rotted source no longer dead-ends a setup. Integrity verification still applies on every fallback.

### Fixed
- **Security:** the Dashboard "Install" button now verifies downloaded APKs against their SHA-256 checksum, matching the setup and update paths (it previously installed unverified).
- Shizuku installs of large/verbose APKs could deadlock on "Installing…" — fixed the output-draining order so they no longer hang.
- GPU driver downloads are now written atomically (no more corrupt half-written driver zips on a disk-full or network drop), with a retry on transient failures, and the driver-discovery call is now cached so repeated setups don't burn the GitHub rate limit.
- The emulator catalog cache and manifest are now written atomically, so a process kill mid-write can't permanently corrupt them.
- A bad catalog filter pattern no longer crashes that emulator's resolution; date-stamped nightly tags with a build suffix no longer show a permanent false "update" badge; "Update all" respects cancellation between items.

### Changed
- Removed dead code and stale comments across the update, install, and source layers; consolidated duplicated helpers.

## v0.3.3

### Fixed
- **Emulator updates now actually install.** Updating an installed emulator could silently do nothing or hang on "Installing…" forever. The emulator install path never checked for the "Install unknown apps" permission (Android 8+) — so without it, the system installer was blocked with no way forward. It now detects the missing permission, shows a clear message, and sends you to the right settings page; a timeout also prevents the install from hanging indefinitely if the system dialog is dismissed.
- **The update UI now shows up.** The Dashboard didn't check for emulator updates when you opened it, and the only "Check for updates" button was hidden inside a banner that only appeared once updates were already found — so on a normal visit there was no way to trigger a check. The Dashboard now checks on entry and always shows a "Check for updates" action.
- Per-app "Update" buttons now disable and show progress the moment you tap them (preventing accidental double-installs), and "Update all" now tells you when there's nothing to update or an update is already running, instead of silently doing nothing.

### Changed
- Removed dead code and stale comments in the update/install system.

## v0.3.2

### Added
- **Discord community.** A "Join the EmuTran community" prompt now appears once on first launch, and a permanent "Join Discord" entry lives on both the Dashboard and the About screen — for help with setup, sharing configs, and reporting issues. Invite: https://discord.gg/jEnMYW5YfE

## v0.3.1

### Fixed
- **In-app update now installs.** The self-updater detected a new release but couldn't install it: it never checked for the per-app "Install unknown apps" permission (required on Android 8+), so the install silently did nothing. It now detects the missing permission, sends you straight to the right settings page, and resumes the install from the already-downloaded file. The install hand-off is also guarded so a failure surfaces a clear message instead of disappearing.
- **Self-update no longer aborts on a network blip.** The SHA-256 integrity check fetched its checksum file exactly once; a single transient hiccup aborted the whole update before anything downloaded. It now retries with backoff and only refuses to install if the checksum is genuinely unreachable (preserving the integrity guarantee).
- **Emulator "update available" badges are now accurate.** Update detection compared a source's release tag against the installed app's version string with a plain text match — two values that almost never line up — so nearly every installed emulator showed a permanent "Update" badge that never cleared, and "Update All" re-downloaded versions you already had. Detection now compares real version numbers and only flags an update when a strictly newer version genuinely exists; when a version can't be determined reliably (e.g. date-stamped or unversioned builds) it no longer shows a false badge.

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
