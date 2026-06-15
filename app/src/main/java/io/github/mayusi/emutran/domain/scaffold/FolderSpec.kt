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

    /**
     * Single source of truth for per-system BIOS info, keyed by the bios sub-folder
     * name (e.g. "ps1" → Emulation/bios/ps1/). Both [biosReadmes] (README text) and
     * [biosFilesBySystem] (structured filename list for the validator) are derived
     * from this list so the two never drift apart.
     *
     * Each raw filename entry may carry a parenthetical/prose note as written in the
     * README (e.g. "scph1001.bin (USA)"). The validator-facing [biosFilesBySystem]
     * strips those notes and skips entries that are prose rather than real filenames
     * (see [cleanBiosFilename]).
     */
    private data class BiosSpec(
        val system: String,
        val filenames: List<String>,
        val usedBy: String,
    )

    private val biosSpecs: Map<String, BiosSpec> = linkedMapOf(
        "ps1" to BiosSpec(
            system = "PlayStation 1",
            filenames = listOf("scph1001.bin (USA)", "scph5501.bin (USA alt)", "scph7001.bin (USA)", "scph1002.bin (PAL)"),
            usedBy = "DuckStation",
        ),
        "ps2" to BiosSpec(
            system = "PlayStation 2",
            filenames = listOf("SCPH-70012.bin (USA)", "SCPH-70004.bin (Europe)", "SCPH-70000.bin (Japan)"),
            usedBy = "NetherSX2 / ARMSX2",
        ),
        "dc" to BiosSpec(
            system = "Dreamcast",
            filenames = listOf("dc_boot.bin", "dc_flash.bin"),
            usedBy = "Flycast",
        ),
        "ds" to BiosSpec(
            system = "Nintendo DS",
            filenames = listOf("bios7.bin", "bios9.bin", "firmware.bin (optional DSi)"),
            usedBy = "MelonDS / MelonDualDS",
        ),
        "3ds" to BiosSpec(
            system = "Nintendo 3DS",
            filenames = listOf("aes_keys.txt (required for encrypted content)"),
            usedBy = "Azahar / Citra MMJ",
        ),
        "gba" to BiosSpec(
            system = "Game Boy Advance",
            filenames = listOf("gba_bios.bin (optional — most cores work without it)"),
            usedBy = "RetroArch (mGBA / VBA-M)",
        ),
        "saturn" to BiosSpec(
            system = "Sega Saturn",
            filenames = listOf("saturn_bios.bin"),
            usedBy = "RetroArch (Beetle Saturn / YabaSanshiro)",
        ),
        "switch" to BiosSpec(
            system = "Nintendo Switch",
            filenames = listOf("prod.keys", "title.keys", "Firmware files (import via emulator UI)"),
            usedBy = "Eden / Citron",
        ),
        "psp" to BiosSpec(
            system = "PlayStation Portable",
            filenames = listOf("PPSSPP usually doesn't need a BIOS. Place firmware files here only if you have a specific reason."),
            usedBy = "PPSSPP",
        ),
        "psvita" to BiosSpec(
            system = "PlayStation Vita",
            filenames = listOf("PSP2UPDAT.PUP (firmware) — install through Vita3K's own installer."),
            usedBy = "Vita3K",
        ),
        "wiiu" to BiosSpec(
            system = "Wii U",
            filenames = listOf("Title keys (where applicable) — Cemu handles most encrypted content via its own keys file."),
            usedBy = "Cemu",
        ),
        "retroarch" to BiosSpec(
            system = "RetroArch system files",
            filenames = listOf("This is RetroArch's 'system' directory. Place core-specific BIOS files here (e.g., scph1001.bin, dc_boot.bin)."),
            usedBy = "RetroArch (all cores)",
        ),
    )

    /** README content keyed by relative path. Dropped after the folder is made. */
    val biosReadmes: Map<String, String> = biosSpecs.entries.associate { (sys, spec) ->
        "Emulation/bios/$sys/README.txt" to bios(spec)
    }

    /**
     * Expected BIOS filenames per system folder name (e.g. "ps1" → ["scph1001.bin", …]),
     * used by the BIOS file validator to check which expected files the user has placed.
     *
     * Parenthetical/prose notes from [biosSpecs] are stripped to clean filenames, and
     * entries that are prose rather than real filenames (e.g. the psp "PPSSPP usually
     * doesn't need…" line) are skipped entirely. Systems whose entries are all prose
     * still appear with an empty list so callers can distinguish "no expected files"
     * from "unknown system".
     */
    val biosFilesBySystem: Map<String, List<String>> = biosSpecs.entries.associate { (sys, spec) ->
        sys to spec.filenames.mapNotNull { cleanBiosFilename(it) }
    }

    /**
     * Strips a parenthetical/note suffix from a README filename entry and returns a
     * clean filename, or null when the entry is prose rather than a real filename.
     *
     * Examples:
     *   "scph1001.bin (USA)"                  → "scph1001.bin"
     *   "gba_bios.bin (optional — …)"         → "gba_bios.bin"
     *   "Firmware files (import via …)"       → null  (no extension; prose)
     *   "PPSSPP usually doesn't need a BIOS…" → null  (prose sentence)
     */
    private fun cleanBiosFilename(raw: String): String? {
        // Take the leading token before any parenthetical/em-dash note, then trim.
        val candidate = raw
            .substringBefore('(')
            .substringBefore('—')
            .trim()

        // A real filename is a single whitespace-free token that has an extension.
        if (candidate.isEmpty()) return null
        if (candidate.any { it.isWhitespace() }) return null
        val dot = candidate.lastIndexOf('.')
        if (dot <= 0 || dot == candidate.lastIndex) return null
        return candidate
    }

    private fun bios(spec: BiosSpec): String =
        buildString {
            appendLine("=== ${spec.system} BIOS ===")
            appendLine()
            appendLine("Place your ${spec.system} BIOS files in this directory.")
            appendLine()
            appendLine("Expected filenames:")
            spec.filenames.forEach { appendLine("  - $it") }
            appendLine()
            appendLine("Used by: ${spec.usedBy}")
            appendLine()
            appendLine("You must legally dump your own BIOS from hardware you own.")
            appendLine("EmuTran does not provide BIOS files.")
        }
}
