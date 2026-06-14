package io.github.mayusi.emutran.data.source

import io.github.mayusi.emutran.data.manifest.AppEntry
import io.github.mayusi.emutran.data.manifest.SystemTag
import java.util.concurrent.ConcurrentHashMap

/**
 * Picks the best asset from a list of release-asset filenames.
 *
 * Mirrors how Obtainium itself does the filtering so users see the same
 * file EmuTran installs as what Obtainium would've picked.
 *
 * For most entries we're looking for APKs. For DRIVERS entries we're
 * looking for ZIPs instead — the K11MCH1 / MrPurple666 / StevenMXZ
 * driver repos ship .zip files containing libvulkan.so + meta.json.
 *
 * Order of preference:
 *   1. Filename matches the entry's explicit apkFilterRegEx (with invert).
 *   2. If autoFilterByArch, prefer arm64-v8a / aarch64 / arm64 in the name.
 *   3. Strip out clearly-wrong arches (x86, x86_64, armeabi) if alternatives.
 *   4. First matching asset remaining.
 */
object ApkAssetFilter {

    /** Common arch tokens we look for in asset filenames. */
    private val ARM64_HINTS = listOf("arm64-v8a", "arm64v8a", "aarch64", "arm64")
    private val OTHER_ARCH_HINTS = listOf("x86_64", "x86-64", "x86", "armeabi-v7a", "armv7", "armeabi")

    // FIX 4: Cache compiled regexes keyed on pattern string to avoid recompiling per call.
    // ConcurrentHashMap is safe for concurrent resolve() calls in the Phase-3 semaphore loop.
    private val regexCache = ConcurrentHashMap<String, Regex>()

    // FIX 4: Maximum allowed pattern length — arbitrary-long patterns from a third-party
    // manifest could be used for ReDoS. Reject anything over this limit.
    private const val MAX_PATTERN_LENGTH = 512

    // FIX 4: Simple catastrophic-backtracking detector.
    // Matches patterns with nested quantifiers that cause exponential backtracking,
    // e.g. (.+)+  (a*)* — the "quantifier over a quantifier" shape.
    private val NESTED_QUANTIFIER_REGEX = Regex("""[+*?]\)+\s*[+*?]|[+*?]\]\s*[+*?]""")

    /**
     * FIX 2: Sanitize a remote filename before it is used in File(dir, name).
     *
     * Guards against path-traversal attacks where a server returns an asset name
     * like "../../etc/cron.d/payload" that would escape cacheDir.
     *
     *   1. Strip any directory component (take the last path segment).
     *   2. Replace characters outside [A-Za-z0-9._-] with '_'.
     *   3. Cap to [MAX_FILENAME_LENGTH] characters.
     *   4. Never produce an empty string (fall back to "download").
     */
    fun sanitizeFilename(raw: String): String {
        // Strip directory components on both slash styles.
        var name = raw.substringAfterLast('/').substringAfterLast('\\')
        // Replace disallowed characters.
        name = name.replace(Regex("[^A-Za-z0-9._\\-]"), "_")
        // Cap length.
        if (name.length > MAX_FILENAME_LENGTH) name = name.take(MAX_FILENAME_LENGTH)
        // Guard empty result.
        if (name.isBlank()) name = "download"
        return name
    }

    /**
     * @return Pair of (filename, AssetKind), or null if nothing matched.
     */
    fun pick(assets: List<String>, entry: AppEntry): Pair<String, AssetKind>? {
        val wantsZip = entry.system == SystemTag.DRIVERS

        // Build the candidate pool. Drivers look for .zip first, fall
        // back to .apk if the repo happens to ship those too. Everything
        // else only considers .apk.
        var pool: List<String>
        var kind: AssetKind
        if (wantsZip) {
            val zips = assets.filter { it.endsWith(".zip", ignoreCase = true) }
            if (zips.isNotEmpty()) {
                pool = zips
                kind = AssetKind.DRIVER_ZIP
            } else {
                pool = assets.filter { it.endsWith(".apk", ignoreCase = true) }
                kind = AssetKind.APK
            }
        } else {
            pool = assets.filter { it.endsWith(".apk", ignoreCase = true) }
            kind = AssetKind.APK
        }
        if (pool.isEmpty()) return null

        // 1. Apply explicit filter regex if set.
        // FIX 4: Guard against ReDoS from manifest-supplied patterns.
        val filter = entry.apkFilterRegEx
        if (filter.isNotBlank()) {
            val regex = compileFilterRegex(filter)
            if (regex != null) {
                val matched = pool.filter { regex.containsMatchIn(it) }
                val filtered = if (entry.invertApkFilter) pool - matched.toSet() else matched
                if (filtered.isNotEmpty()) pool = filtered
            }
        }

        // 2. Arch preference (still applies to driver zips — many are
        //    suffixed _aarch64 or similar).
        if (entry.autoFilterByArch) {
            val arm64 = pool.filter { name -> ARM64_HINTS.any { it in name.lowercase() } }
            if (arm64.isNotEmpty()) return arm64.first() to kind

            val withoutWrongArch = pool.filter { name ->
                OTHER_ARCH_HINTS.none { it in name.lowercase() }
            }
            if (withoutWrongArch.isNotEmpty()) return withoutWrongArch.first() to kind
        }

        return pool.firstOrNull()?.let { it to kind }
    }

    /**
     * FIX 4: Compile [pattern] to a [Regex], guarding against ReDoS.
     *
     * Rejects:
     *   - Patterns longer than [MAX_PATTERN_LENGTH] characters.
     *   - Patterns containing obvious nested-quantifier shapes that cause
     *     catastrophic backtracking (e.g. `(.+)+`, `(a*)*`).
     *
     * Results are cached in [regexCache] so each distinct pattern is only
     * compiled once across multiple resolve() calls.
     *
     * Returns null on rejection or compile failure (caller skips filtering).
     */
    private fun compileFilterRegex(pattern: String): Regex? {
        if (pattern.length > MAX_PATTERN_LENGTH) {
            android.util.Log.w(TAG, "apkFilterRegEx rejected: length ${pattern.length} > $MAX_PATTERN_LENGTH")
            return null
        }
        if (NESTED_QUANTIFIER_REGEX.containsMatchIn(pattern)) {
            android.util.Log.w(TAG, "apkFilterRegEx rejected: suspected nested quantifiers in '$pattern'")
            return null
        }
        return regexCache.getOrPut(pattern) {
            runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }.getOrNull() ?: return null
        }
    }

    private const val MAX_FILENAME_LENGTH = 128
    private const val TAG = "ApkAssetFilter"
}
