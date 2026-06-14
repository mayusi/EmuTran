package io.github.mayusi.emutran.data.manifest

/**
 * Clean, app-facing view of one emulator/utility from the manifest.
 * This is what the picker, downloader, and installer all work with.
 *
 * Built by flattening RawAppEntry + parsed AppAdditionalSettings into
 * one shape with no Obtainium-isms leaking through.
 */
data class AppEntry(
    val id: String,                  // canonical package-style id from the pack
    val name: String,
    val author: String,
    val about: String,                // short description, may be empty
    val sourceUrl: String,            // GitHub repo URL / Codeberg URL / mirror URL
    val source: SourceKind,
    val apkFilterRegEx: String,       // regex applied to release-asset filenames
    val invertApkFilter: Boolean,
    val autoFilterByArch: Boolean,    // when true, source can pick arm64 asset itself
    val includePrereleases: Boolean,
    val fallbackToOlderReleases: Boolean,
    val versionExtractionRegEx: String,
    val filterReleaseTitlesRegEx: String,
    val categories: List<String>,
    val trackOnly: Boolean,           // hide from picker; user gets manual install note
    val system: SystemTag,            // grouping for the picker UI
    val recommended: Boolean,         // pre-selected when picker first opens
    val mutuallyExclusiveGroup: String? = null, // shared key = pick one of group
)

/** Where we fetch this app's APK from. Each backed by a different AppSource. */
enum class SourceKind {
    GITHUB,           // api.github.com/repos/.../releases/latest
    GITEA,            // Eden — uses Gitea API (Codeberg / git.eden-emu.dev)
    HTML_SCRAPE,      // Dolphin, DuckStation mirror, PPSSPP, ScummVM, etc.
    UNKNOWN,          // Anything we don't yet handle — listed but disabled
}

/**
 * Coarse system-by-system grouping used to sort the picker UI into
 * sections. Not all manifest entries map cleanly so we keep an OTHER bucket.
 */
enum class SystemTag(val display: String, val order: Int) {
    RETRO("Retro / multi-system", 0),
    NINTENDO_CONSOLE("Nintendo (console)", 1),
    NINTENDO_HANDHELD("Nintendo (handheld)", 2),
    PLAYSTATION("PlayStation", 3),
    SEGA("Sega", 4),
    PC_WINDOWS("Windows / PC", 5),
    FRONTEND("Frontends & launchers", 6),
    UTILITY("Utilities", 7),
    DRIVERS("Drivers", 8),
    OTHER("Other", 9),
}
