package io.github.mayusi.emutran.domain.roms

/**
 * Classifies ROM files by their extension into the canonical system folder
 * names from [io.github.mayusi.emutran.domain.scaffold.FolderSpec].
 *
 * Rules:
 *  - Extensions are lowercased before lookup — case never matters.
 *  - Extensions that belong unambiguously to one system → [Classification.KnownSystem].
 *  - Extensions shared across multiple systems (e.g. .iso, .bin, .chd) →
 *    [Classification.Ambiguous] with the candidate list so the user can pick.
 *  - Anything else → [Classification.Unknown] (unrecognised file type).
 *
 * The folder names in [extensionMap] and [ambiguousExtensionMap] MUST match
 * the directory names in FolderSpec.tree (under "Emulation/roms/<system>").
 * If FolderSpec adds a new system, update the maps here too.
 */
object RomClassifier {

    /** Result of classifying a file extension. */
    sealed class Classification {
        /**
         * Extension maps to exactly one system.
         * [dir] is the roms sub-folder name (e.g. "gba", "n64", "snes").
         */
        data class KnownSystem(val dir: String) : Classification()

        /**
         * Extension is shared across multiple systems; the user must pick.
         * [candidates] are the candidate system folder names, ordered by
         * likelihood (most common host first).
         */
        data class Ambiguous(val candidates: List<String>) : Classification()

        /**
         * Extension is not in any known ROM category.
         * This is NOT an error — it means the file is not a ROM we can sort.
         */
        data object Unknown : Classification()
    }

    // ── Unambiguous extension → single system ─────────────────────────────────

    /**
     * Extensions that map to exactly one system.
     *
     * Key: lowercase extension WITHOUT leading dot (e.g. "nds", not ".nds").
     * Value: folder name under Emulation/roms/ (from FolderSpec.tree).
     */
    private val extensionMap: Map<String, String> = mapOf(
        // ── Nintendo DS / 3DS ────────────────────────────────────────────────
        "nds"   to "ds",         // Nintendo DS ROM
        "3ds"   to "3ds",        // Nintendo 3DS ROM (decrypted)
        "cia"   to "3ds",        // Nintendo 3DS installable archive

        // ── Game Boy family ──────────────────────────────────────────────────
        "gb"    to "gb",         // Game Boy (original)
        "gbc"   to "gbc",        // Game Boy Color
        "gba"   to "gba",        // Game Boy Advance

        // ── NES / SNES ───────────────────────────────────────────────────────
        "nes"   to "nes",        // Nintendo Entertainment System
        "fds"   to "nes",        // Famicom Disk System (played via NES emulator)
        "sfc"   to "snes",       // Super Famicom (Japanese naming)
        "smc"   to "snes",       // Super Nintendo (older extension)
        "fig"   to "snes",       // Super Nintendo (rare extension)

        // ── Nintendo 64 ──────────────────────────────────────────────────────
        "z64"   to "n64",        // Nintendo 64 (big-endian / native)
        "n64"   to "n64",        // Nintendo 64 (little-endian / byteswapped)
        "v64"   to "n64",        // Nintendo 64 (byte-swapped)

        // ── GameCube ─────────────────────────────────────────────────────────
        "gcm"   to "gc",         // GameCube ROM (uncompressed disc image)
        "rvz"   to "gc",         // GameCube / Wii compressed format (Dolphin)
        "dol"   to "gc",         // GameCube executable (homebrew)

        // ── Wii ──────────────────────────────────────────────────────────────
        "wbfs"  to "wii",        // Wii Backup File System (single-game)

        // ── PSP ──────────────────────────────────────────────────────────────
        "pbp"   to "psp",        // PSP EBOOT / PSX on PSP title

        // ── PS Vita ──────────────────────────────────────────────────────────
        "vpk"   to "psvita",     // PS Vita software package

        // ── Sega Genesis / Mega Drive ────────────────────────────────────────
        "md"    to "genesis",    // Mega Drive ROM
        "gen"   to "genesis",    // Genesis ROM
        "smd"   to "genesis",    // Sega Mega Drive (interleaved format)
        "32x"   to "genesis",    // Sega 32X addon (RetroArch uses genesis folder)

        // ── Sega Master System ───────────────────────────────────────────────
        "sms"   to "mastersystem",

        // ── Sega Game Gear ───────────────────────────────────────────────────
        "gg"    to "gamegear",

        // ── Sega Saturn ──────────────────────────────────────────────────────
        // .iso / .bin / .cue are ambiguous (see ambiguousExtensionMap below).
        // .mds + .mdf are also Saturn-common but still ambiguous.

        // ── Atari 2600 / 5200 / 7800 ────────────────────────────────────────
        "a26"   to "atari2600",
        "a52"   to "atari5200",
        "a78"   to "atari7800",

        // ── PC Engine / TurboGrafx ───────────────────────────────────────────
        "pce"   to "pce",        // PC Engine ROM
        "tg16"  to "pce",        // TurboGrafx-16 ROM

        // ── Neo Geo Pocket ───────────────────────────────────────────────────
        "ngp"   to "ngp",        // Neo Geo Pocket
        "ngc"   to "ngp",        // Neo Geo Pocket Color (same folder per FolderSpec)

        // ── WonderSwan ───────────────────────────────────────────────────────
        "ws"    to "wonderswan",
        "wsc"   to "wonderswan", // WonderSwan Color

        // ── Arcade ───────────────────────────────────────────────────────────
        // .zip is ambiguous, but .7z arcade-only zips are too risky to classify.
        // Individual MAME ROMs with these known extensions are safe:
        "lnx"   to "arcade",     // Atari Lynx (MAME/RetroArch uses arcade set)

        // ── Switch ───────────────────────────────────────────────────────────
        "nsp"   to "switch",     // Nintendo Switch package (not a game dump per se —
                                 // but this is where Eden / Citron expect it)
        "xci"   to "switch",     // Nintendo Switch cartridge image
        "nca"   to "switch",     // Nintendo Switch content archive (component)

        // ── Wii U ────────────────────────────────────────────────────────────
        "wud"   to "wiiu",       // Wii U disc image (uncompressed)
        "wux"   to "wiiu",       // Wii U disc image (compressed)
        "rpx"   to "wiiu",       // Wii U executable (extracted RPX/RPL)

        // ── PlayStation 3 ────────────────────────────────────────────────────
        // PS3 content is almost always folder-based (RPCS3 game folders), so
        // individual-file extensions are rare. EDAT/SPRX are internal — skip.

        // ── ScummVM ──────────────────────────────────────────────────────────
        // ScummVM games are folder-based; no single ROM extension exists.

        // ── DOS ──────────────────────────────────────────────────────────────
        // DOSBox games are folder-based too; no single ROM extension.
    )

    // ── Ambiguous extension → multiple possible systems ───────────────────────

    /**
     * Extensions that are commonly used by MORE than one system.
     * Candidates are ordered by rough global prevalence (most common first).
     *
     * Key: lowercase extension without dot.
     * Value: ordered list of candidate system folder names.
     */
    private val ambiguousExtensionMap: Map<String, List<String>> = mapOf(
        // .iso — used by PS1, PS2, PSP (UMD-rip), GameCube, Wii, Dreamcast, Saturn
        "iso"  to listOf("ps2", "ps1", "psp", "gc", "wii", "dc", "saturn"),

        // .bin — PS1 track file, Saturn, Dreamcast, Mega-CD, SNES (headered)
        "bin"  to listOf("ps1", "saturn", "dc", "genesis", "snes"),

        // .cue — cue sheet pairing with .bin; same set of systems
        "cue"  to listOf("ps1", "saturn", "dc", "genesis"),

        // .chd — MAME Compressed Hunks of Data; used by PS1, PS2, PSP, DC, Saturn, arcade
        "chd"  to listOf("ps1", "ps2", "psp", "dc", "saturn", "arcade"),

        // .cso — Compressed ISO used primarily for PSP but also PS2 (OPL)
        "cso"  to listOf("psp", "ps2"),

        // .zso — newer compression format, primarily PSP
        "zso"  to listOf("psp", "ps2"),

        // .img — Sega CD, Dreamcast, Saturn, older CD images
        "img"  to listOf("dc", "saturn", "genesis"),

        // .mdf / .mds — Alcohol 120% format; Saturn and Dreamcast most common
        "mdf"  to listOf("saturn", "dc"),
        "mds"  to listOf("saturn", "dc"),

        // .zip — arcade ROMs (MAME), but also used to compress almost anything
        "zip"  to listOf("arcade", "nes", "snes", "genesis", "gba"),

        // .7z — same reasoning as .zip
        "7z"   to listOf("arcade", "nes", "snes", "genesis", "gba"),
    )

    /**
     * Returns the [Classification] for the given [extension].
     *
     * @param extension The file extension WITH or WITHOUT a leading dot
     *   (both are accepted). Case-insensitive.
     */
    fun classify(extension: String): Classification {
        val normalized = extension.trimStart('.').lowercase()

        extensionMap[normalized]?.let { dir ->
            return Classification.KnownSystem(dir)
        }

        ambiguousExtensionMap[normalized]?.let { candidates ->
            return Classification.Ambiguous(candidates)
        }

        return Classification.Unknown
    }

    /**
     * Convenience: classify by full filename.
     * Extracts the extension from the last '.' in the filename.
     * Returns [Classification.Unknown] if the file has no extension.
     */
    fun classifyFile(filename: String): Classification {
        val dot = filename.lastIndexOf('.')
        if (dot < 0 || dot == filename.lastIndex) return Classification.Unknown
        return classify(filename.substring(dot + 1))
    }

    /**
     * The complete set of extensions the classifier recognises (both
     * unambiguous and ambiguous). Useful as a fast pre-filter before opening
     * a File to check its extension.
     */
    val knownExtensions: Set<String> =
        extensionMap.keys + ambiguousExtensionMap.keys
}
