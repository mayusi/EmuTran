# EmuTran

[![Latest release](https://img.shields.io/github/v/release/mayusi/EmuTran?style=flat-square&label=release&color=2ea043)](https://github.com/mayusi/EmuTran/releases/latest)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue?style=flat-square)](LICENSE)
[![Android 10+](https://img.shields.io/badge/android-10%2B-555?style=flat-square)](#requirements)

**One-tap emulation setup for Android handhelds.**

EmuTran turns the tedious, multi-hour job of setting up an Android gaming handheld into a single guided flow. It detects your device, lets you pick your emulators from a curated catalog, downloads them from their official sources, installs them silently (or with standard prompts if Shizuku isn't available), and builds the full `Emulation/` folder tree — all in one go. Built for Retroid Pocket, AYN Odin, and Anbernic owners who want a clean setup without the forum archaeology.

<p align="center">
  <a href="https://github.com/mayusi/EmuTran/releases/latest"><img src="https://img.shields.io/badge/Download%20APK-2ea043?style=for-the-badge&logo=android&logoColor=white" alt="Download the latest APK"></a>
</p>

The whole app is **controller-first** — every screen is navigable with the D-pad and face buttons, no touchscreen required.

---

## What it does

- **Detects your device** — reads manufacturer, SoC, and screen type to surface relevant emulators and skip ones that don't apply to your hardware.
- **Curated emulator catalog** — backed by the [Obtainium-Emulation-Pack](https://github.com/RJNY/Obtainium-Emulation-Pack) manifest; 40+ emulators across Nintendo, PlayStation, Sega, retro multi-system, and more.
- **Batch silent install** — uses [Shizuku](https://github.com/RikkaApps/Shizuku) for fully silent installs without a prompt per app, and falls back to standard installer dialogs when Shizuku isn't available.
- **Emulation folder scaffold** — builds the complete `Emulation/` tree with a BIOS subfolder per system and a README in each explaining what belongs there.
- **GPU driver staging** — for Adreno devices, optionally stages community drivers from [AdrenoToolsDrivers](https://github.com/K11MCH1/AdrenoToolsDrivers) for emulators that support custom GPU drivers.
- **Management dashboard** — after setup, see installed emulators, available updates, run a Setup Health Check, and add more from the catalog any time.
- **In-app self-update** — checks for new EmuTran versions in the background, shows the patch notes, verifies the download against a SHA-256 sidecar, and hands off to the system installer.

## What it does not do

- No ROMs, ISOs, or game files — you supply those.
- No BIOS files — it creates the folders and notes what goes where; the files are yours to provide.
- No root, no system modification — everything runs in user space.
- No analytics, no telemetry, no tracking — the only network calls are fetching emulator/driver downloads, the catalog manifest, and the GitHub release API for self-updates.

---

## Requirements

- ARM64 Android handheld, **Android 10 (API 29)** or later.

| Device | Status |
| :--- | :---: |
| Retroid Pocket 6 (SD 8 Gen 2 / Adreno 740) | Tested |
| Retroid Pocket 4 / 4 Pro / 5 | Expected |
| AYN Odin 2 / Odin 3 | Expected |
| Anbernic RG-series (Android, ARM64) | Expected |
| Any ARM64 Android 10+ handheld | Should work |

GPU driver staging is Adreno-only. Mali/Dimensity devices install everything else fine; the driver step is skipped automatically.

---

## Install

1. Download the latest APK from the [**Releases**](https://github.com/mayusi/EmuTran/releases/latest) page.
2. Open it on your handheld to sideload — Android will prompt you to allow installs once.
3. Follow the permissions wizard: notifications (download progress), all-files access (to build the Emulation folder), and install unknown apps (to install the emulators).

No account, no telemetry, no subscription.

---

## Credits

- [Obtainium-Emulation-Pack](https://github.com/RJNY/Obtainium-Emulation-Pack) by RJNY — the emulator catalog EmuTran builds on.
- [Shizuku](https://github.com/RikkaApps/Shizuku) by RikkaApps — the silent-install backbone.
- [AdrenoToolsDrivers](https://github.com/K11MCH1/AdrenoToolsDrivers) by K11MCH1 — the community Adreno driver repository.

## License

[MIT](LICENSE) — © mayusi
