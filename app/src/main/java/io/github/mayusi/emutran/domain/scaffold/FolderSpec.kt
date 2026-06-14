package io.github.mayusi.emutran.domain.scaffold

/**
 * The full Emulation/ tree we lay down on the user's chosen storage root.
 *
 * Keep folder names lowercase and matching the conventions used by ES-DE,
 * Cocoon Shell, Daijishō, etc. so frontends can auto-detect platforms.
 *
 * BIOS dirs get a README dropped via [biosReadmes]; everything else is
 * just an empty folder waiting for content.
 */
object FolderSpec {

    /** Every folder we create, relative to the user-picked root. */
    val tree: List<String> = buildList {
        add("Emulation")
        listOf(
            "roms", "saves", "states", "screenshots",
            "covers", "cheats", "bios", "configs", "textures", "tools",
        ).forEach { add("Emulation/$it") }

        listOf(
            "3ds", "atari2600", "atari5200", "atari7800", "atomiswave",
            "dc", "dos", "ds", "gamegear", "gba", "gbc", "gb", "gc",
            "genesis", "mastersystem", "n64", "naomi", "nes", "ngp",
            "pce", "ps1", "ps2", "ps3", "psp", "psvita", "saturn",
            "scummvm", "snes", "switch", "wii", "wiiu", "wonderswan",
            "arcade",
        ).forEach { sys ->
            add("Emulation/roms/$sys")
            add("Emulation/saves/$sys")
            add("Emulation/states/$sys")
        }

        listOf(
            "ps1", "ps2", "dc", "ds", "3ds", "gba", "saturn", "switch",
            "psp", "psvita", "retroarch", "wiiu",
        ).forEach { add("Emulation/bios/$it") }

        listOf("gc", "wii", "n64").forEach { add("Emulation/textures/$it") }

        add("Emulation/tools/turnip")
        add("Emulation/tools/retroarch_cores")
        add("Emulation/tools/shaders")
    }

    /** README content keyed by relative path. Dropped after the folder is made. */
    val biosReadmes: Map<String, String> = mapOf(
        "Emulation/bios/ps1/README.txt" to bios(
            system = "PlayStation 1",
            filenames = listOf("scph1001.bin (USA)", "scph5501.bin (USA alt)", "scph7001.bin (USA)", "scph1002.bin (PAL)"),
            usedBy = "DuckStation",
        ),
        "Emulation/bios/ps2/README.txt" to bios(
            system = "PlayStation 2",
            filenames = listOf("SCPH-70012.bin (USA)", "SCPH-70004.bin (Europe)", "SCPH-70000.bin (Japan)"),
            usedBy = "NetherSX2 / ARMSX2",
        ),
        "Emulation/bios/dc/README.txt" to bios(
            system = "Dreamcast",
            filenames = listOf("dc_boot.bin", "dc_flash.bin"),
            usedBy = "Flycast",
        ),
        "Emulation/bios/ds/README.txt" to bios(
            system = "Nintendo DS",
            filenames = listOf("bios7.bin", "bios9.bin", "firmware.bin (optional DSi)"),
            usedBy = "MelonDS / MelonDualDS",
        ),
        "Emulation/bios/3ds/README.txt" to bios(
            system = "Nintendo 3DS",
            filenames = listOf("aes_keys.txt (required for encrypted content)"),
            usedBy = "Azahar / Citra MMJ",
        ),
        "Emulation/bios/gba/README.txt" to bios(
            system = "Game Boy Advance",
            filenames = listOf("gba_bios.bin (optional — most cores work without it)"),
            usedBy = "RetroArch (mGBA / VBA-M)",
        ),
        "Emulation/bios/saturn/README.txt" to bios(
            system = "Sega Saturn",
            filenames = listOf("saturn_bios.bin"),
            usedBy = "RetroArch (Beetle Saturn / YabaSanshiro)",
        ),
        "Emulation/bios/switch/README.txt" to bios(
            system = "Nintendo Switch",
            filenames = listOf("prod.keys", "title.keys", "Firmware files (import via emulator UI)"),
            usedBy = "Eden / Citron",
        ),
        "Emulation/bios/psp/README.txt" to bios(
            system = "PlayStation Portable",
            filenames = listOf("PPSSPP usually doesn't need a BIOS. Place firmware files here only if you have a specific reason."),
            usedBy = "PPSSPP",
        ),
        "Emulation/bios/psvita/README.txt" to bios(
            system = "PlayStation Vita",
            filenames = listOf("PSP2UPDAT.PUP (firmware) — install through Vita3K's own installer."),
            usedBy = "Vita3K",
        ),
        "Emulation/bios/wiiu/README.txt" to bios(
            system = "Wii U",
            filenames = listOf("Title keys (where applicable) — Cemu handles most encrypted content via its own keys file."),
            usedBy = "Cemu",
        ),
        "Emulation/bios/retroarch/README.txt" to bios(
            system = "RetroArch system files",
            filenames = listOf("This is RetroArch's 'system' directory. Place core-specific BIOS files here (e.g., scph1001.bin, dc_boot.bin)."),
            usedBy = "RetroArch (all cores)",
        ),
    )

    private fun bios(system: String, filenames: List<String>, usedBy: String): String =
        buildString {
            appendLine("=== $system BIOS ===")
            appendLine()
            appendLine("Place your $system BIOS files in this directory.")
            appendLine()
            appendLine("Expected filenames:")
            filenames.forEach { appendLine("  - $it") }
            appendLine()
            appendLine("Used by: $usedBy")
            appendLine()
            appendLine("You must legally dump your own BIOS from hardware you own.")
            appendLine("EmuTran does not provide BIOS files.")
        }
}
