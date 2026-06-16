package io.github.mayusi.emutran.data.source

import io.github.mayusi.emutran.data.manifest.AppEntry
import io.github.mayusi.emutran.data.manifest.SourceKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/**
 * HTML-scrape resolver with per-host strategies for the entries whose
 * APKs don't live behind GitHub/Gitea.
 *
 * Two phases:
 *   1. Try a host-specific extractor (see [hostStrategies]). These handle
 *      sites with multi-level autoindexes (RetroArch, Play!) or JS-heavy
 *      pages that need a known URL pattern (Dolphin).
 *   2. Fall back to the generic "scan one page for .apk links" extractor.
 *
 * A real browser User-Agent is sent everywhere because a bunch of
 * download sites 403 on default OkHttp/java UAs (Dolphin's nginx
 * config notably).
 */
@Singleton
class HtmlScrapeSource @Inject constructor(
    private val client: OkHttpClient,
) : AppSource {

    override suspend fun resolve(entry: AppEntry): ResolveResult = withContext(Dispatchers.IO) {
        if (entry.source != SourceKind.HTML_SCRAPE) return@withContext ResolveResult.Unsupported

        // Try host-specific strategy first.
        val host = runCatching {
            java.net.URI(entry.sourceUrl).host?.lowercase().orEmpty()
        }.getOrDefault("")
        val strategy = hostStrategies.entries.firstOrNull { (key, _) -> key in host }?.value
        if (strategy != null) {
            val r = strategy(entry, ::fetchHtml)
            if (r !is ResolveResult.Failed) return@withContext r
            // Strategy explicitly failed → still try generic as last resort.
        }

        // Generic: one-page .apk href scan, filtered by entry's regex / arch.
        return@withContext genericResolve(entry)
    }

    // ------------------------------------------------------------------
    // Strategy infrastructure
    // ------------------------------------------------------------------

    /**
     * Strategy = (entry, fetchHtml) → ResolveResult.
     * fetchHtml passed in for testability and to share the configured
     * OkHttpClient with the real UA.
     */
    private val hostStrategies: Map<String, suspend (AppEntry, suspend (String) -> String?) -> ResolveResult> = mapOf(
        "buildbot.libretro.com" to ::resolveRetroArchBuildbot,
        "purei.org" to ::resolvePlayAutoindex,
        "dolphin-emu.org" to ::resolveDolphinSite,
    )

    /**
     * RetroArch buildbot: /stable/ lists version dirs ("1.10.0/", "1.21.0/"
     * etc.), each contains /android/ which contains the .apk. We pick
     * the highest-version dir and descend into it.
     */
    private suspend fun resolveRetroArchBuildbot(
        entry: AppEntry,
        fetch: suspend (String) -> String?,
    ): ResolveResult {
        // Step 1: scrape /stable/ for version subdirs.
        val rootHtml = fetch(entry.sourceUrl)
            ?: return ResolveResult.Failed("Could not fetch ${entry.sourceUrl}")
        val versions = VERSION_DIR_REGEX.findAll(rootHtml)
            .map { it.groupValues[1] }
            .distinct()
            .sortedWith(compareByDescending(versionComparator()) { it })
            .toList()
        if (versions.isEmpty()) return ResolveResult.Failed("No version dirs found at root")

        // Step 2: walk versions newest → oldest until one has an APK.
        val baseUrl = entry.sourceUrl.trimEnd('/')
        for (version in versions) {
            val androidUrl = "$baseUrl/$version/android"
            val androidHtml = fetch(androidUrl) ?: continue
            // The arch is usually a folder like arm64-v8a/, or the .apk
            // sits flat in /android/. Try both.
            val apkHrefs = APK_LINK_REGEX.findAll(androidHtml).map { it.groupValues[1] }.toList()
            if (apkHrefs.isNotEmpty()) {
                val names = apkHrefs.map { it.substringAfterLast('/') }
                val picked = ApkAssetFilter.pick(names, entry) ?: continue
                val (pickedName, kind) = picked
                val absolute = resolveAgainstBase("$androidUrl/", apkHrefs.first { it.endsWith(pickedName) })
                return ResolveResult.Found(
                    apkUrl = absolute,
                    // FIX 2: sanitize scraped filename before it reaches File(cacheDir, name).
                    filename = ApkAssetFilter.sanitizeFilename(pickedName),
                    version = version, sizeBytes = null, kind = kind,
                )
            }
            // Otherwise look for arch subdirs.
            val archDirs = ARCH_DIR_REGEX.findAll(androidHtml).map { it.groupValues[1] }.distinct().toList()
            val preferredArchOrder = listOf("arm64-v8a", "arm64", "aarch64")
            val arch = preferredArchOrder.firstOrNull { it in archDirs } ?: archDirs.firstOrNull() ?: continue
            val archUrl = "$androidUrl/$arch"
            val archHtml = fetch(archUrl) ?: continue
            val apks = APK_LINK_REGEX.findAll(archHtml).map { it.groupValues[1] }.toList()
            val firstApk = apks.firstOrNull() ?: continue
            val rawFilename = firstApk.substringAfterLast('/')
            val abs = resolveAgainstBase("$archUrl/", firstApk)
            return ResolveResult.Found(
                apkUrl = abs,
                // FIX 2: sanitize scraped filename.
                filename = ApkAssetFilter.sanitizeFilename(rawFilename),
                // Emit the clean numeric version ("1.19.1") rather than
                // "1.19.1-arm64-v8a": the arch suffix is download-arch metadata,
                // not part of the version, and a clean tag keeps the version
                // label readable. The update check parses leading numerics
                // either way, so this does not change update detection.
                version = version, sizeBytes = null,
                kind = AssetKind.APK,
            )
        }
        return ResolveResult.Failed("No APK found in any version dir")
    }

    /**
     * Play! emulator: same Apache-autoindex pattern as buildbot.
     * Versions are numeric directories like 0.42/, 0.43/. Latest dir
     * contains the .apk directly.
     */
    private suspend fun resolvePlayAutoindex(
        entry: AppEntry,
        fetch: suspend (String) -> String?,
    ): ResolveResult {
        val rootHtml = fetch(entry.sourceUrl)
            ?: return ResolveResult.Failed("Could not fetch ${entry.sourceUrl}")
        val versions = VERSION_DIR_REGEX.findAll(rootHtml)
            .map { it.groupValues[1] }
            .distinct()
            .sortedWith(compareByDescending(versionComparator()) { it })
            .toList()
        if (versions.isEmpty()) return ResolveResult.Failed("No version dirs found")
        val baseUrl = entry.sourceUrl.trimEnd('/')
        for (version in versions) {
            val verUrl = "$baseUrl/$version"
            val verHtml = fetch(verUrl) ?: continue
            val apkHrefs = APK_LINK_REGEX.findAll(verHtml).map { it.groupValues[1] }.toList()
            if (apkHrefs.isEmpty()) continue
            val names = apkHrefs.map { it.substringAfterLast('/') }
            val picked = ApkAssetFilter.pick(names, entry)
            val pickedName = picked?.first ?: names.first()
            val kind = picked?.second ?: AssetKind.APK
            val abs = resolveAgainstBase("$verUrl/", apkHrefs.first { it.endsWith(pickedName) })
            return ResolveResult.Found(
                apkUrl = abs,
                // FIX 2: sanitize scraped filename.
                filename = ApkAssetFilter.sanitizeFilename(pickedName),
                version = version, sizeBytes = null, kind = kind,
            )
        }
        return ResolveResult.Failed("No APK found in any version dir")
    }

    /**
     * Dolphin's official download page is React-rendered — the APK URL
     * isn't in the raw HTML. They DO publish a stable JSON-y endpoint
     * but the most reliable strategy is to query their releases API
     * directly (works the same as GitHub releases).
     *
     * Dolphin's "stable" Android build is mirrored on GitHub at
     * dolphin-emu/dolphin-android via a separate repo, but the official
     * recommended source is dolphin-emu.org/download which serves JSON
     * via /update/check/v1/dolphin-master/<rev>/. For v0.x we use a
     * simpler heuristic: hit the public releases JSON.
     *
     * Update: there's no public JSON we can rely on without a session.
     * The cleanest workable path is to fetch the download page with a
     * real UA and extract any direct CDN .apk URL from the inlined JSON
     * the site bootstraps.
     */
    private suspend fun resolveDolphinSite(
        entry: AppEntry,
        fetch: suspend (String) -> String?,
    ): ResolveResult {
        val html = fetch(entry.sourceUrl)
            ?: return ResolveResult.Failed("Could not fetch ${entry.sourceUrl}")
        // Dolphin's page inlines build links in JSON-like blocks. Match
        // any URL ending in .apk on dolphin-emu.org or its CDN.
        val apkUrls = DOLPHIN_APK_URL_REGEX.findAll(html).map { it.value }.distinct().toList()
        if (apkUrls.isEmpty()) {
            return ResolveResult.Failed(
                "No APK URL found in Dolphin page — they may have changed their HTML"
            )
        }
        val picked = apkUrls.first()
        return ResolveResult.Found(
            apkUrl = picked,
            // FIX 2: sanitize scraped filename from Dolphin CDN URL.
            filename = ApkAssetFilter.sanitizeFilename(picked.substringAfterLast('/')),
            version = "unknown",
            sizeBytes = null,
        )
    }

    // ------------------------------------------------------------------
    // Generic fallback
    // ------------------------------------------------------------------

    private suspend fun genericResolve(entry: AppEntry): ResolveResult {
        val html = fetchHtml(entry.sourceUrl)
            ?: return ResolveResult.Failed("Could not fetch ${entry.sourceUrl}")

        val apkLinks = APK_LINK_REGEX.findAll(html)
            .map { it.groupValues[1] }
            .map { resolveAgainstBase(entry.sourceUrl, it) }
            .distinct()
            .toList()
        if (apkLinks.isEmpty()) {
            return ResolveResult.Failed("No .apk links found at ${entry.sourceUrl}")
        }
        val names = apkLinks.map { it.substringAfterLast('/') }
        val picked = ApkAssetFilter.pick(names, entry)
            ?: return ResolveResult.Failed("No matching APK on ${entry.sourceUrl}")
        val (pickedName, kind) = picked
        val url = apkLinks.first { it.endsWith(pickedName) }
        return ResolveResult.Found(
            apkUrl = url,
            // FIX 2: sanitize scraped filename before it reaches File(cacheDir, name).
            filename = ApkAssetFilter.sanitizeFilename(pickedName),
            version = "unknown", sizeBytes = null, kind = kind,
        )
    }

    /**
     * HTTP fetcher with a browser-ish UA so sites that 403 on java UAs work.
     *
     * Per-host UA override: some hosts respond 200 to Obtainium's own UA but
     * 403 to a browser UA (or vice-versa). [HOST_UA_OVERRIDES] maps a host
     * substring to the UA string to send for that host. If no override matches,
     * [BROWSER_UA] is used.
     *
     * Currently overridden hosts:
     *  - dolphin-emu.org: returns 403 to browser UAs but 200 to "Obtainium/1.0".
     *    Their nginx config was deliberately configured this way. Sending the
     *    Obtainium UA restores access and the DOLPHIN_APK_URL_REGEX then extracts
     *    the CDN .apk URL from the page's inlined JSON.
     */
    private suspend fun fetchHtml(url: String): String? = withContext(Dispatchers.IO) {
        val host = runCatching { java.net.URI(url).host?.lowercase().orEmpty() }.getOrDefault("")
        val ua = HOST_UA_OVERRIDES.entries
            .firstOrNull { (hostSubstring, _) -> hostSubstring in host }
            ?.value
            ?: BROWSER_UA

        val req = Request.Builder()
            .url(url)
            .header("User-Agent", ua)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
        try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                resp.body?.string()
            }
        } catch (t: Throwable) {
            null
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Comparator that sorts "1.10.0" > "1.9.5" by numeric segments. */
    private fun versionComparator(): Comparator<String> = Comparator { a, b ->
        val pa = parseVersionKey(a)
        val pb = parseVersionKey(b)
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val ai = pa.getOrElse(i) { 0 }
            val bi = pb.getOrElse(i) { 0 }
            if (ai != bi) return@Comparator ai.compareTo(bi)
        }
        0
    }

    /** Parse a version-like string into a sortable list of ints. */
    private fun parseVersionKey(v: String): List<Int> =
        v.trim('/').split('.', '-')
            .map { part -> part.toIntOrNull() ?: 0 }

    /** Site-relative href → absolute URL. */
    private fun resolveAgainstBase(baseUrl: String, link: String): String = when {
        link.startsWith("http://") || link.startsWith("https://") -> link
        link.startsWith("//") -> "https:$link"
        link.startsWith("/") -> {
            val origin = ORIGIN_REGEX.find(baseUrl)?.value ?: baseUrl
            "$origin$link"
        }
        else -> {
            val baseDir = if (baseUrl.endsWith("/")) baseUrl else baseUrl.substringBeforeLast('/') + "/"
            "$baseDir$link"
        }
    }

    companion object {
        /** Extracts the scheme+host origin from a base URL (for root-relative hrefs). */
        private val ORIGIN_REGEX = Regex("""^(https?://[^/]+)""")

        /** Chrome on Android UA. Boring & widely accepted. */
        private const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 13; Odin3) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        /**
         * Per-host User-Agent overrides.
         *
         * Some download servers allow or block specific UAs at the nginx/CDN
         * level. For those hosts we send a targeted UA instead of [BROWSER_UA].
         *
         * Maps host substring → UA string. The first matching entry wins.
         *
         * dolphin-emu.org: Their nginx config returns 403 to browser UAs but
         * serves the download page (including inlined APK JSON) correctly when
         * the UA is "Obtainium/1.0". Do NOT change this to BROWSER_UA — it
         * will break Dolphin downloads for all users.
         */
        private val HOST_UA_OVERRIDES: Map<String, String> = mapOf(
            "dolphin-emu.org" to "Obtainium/1.0",
        )

        /** Any href/src ending in .apk (with optional query string). */
        private val APK_LINK_REGEX =
            Regex("""(?:href|src)\s*=\s*["']([^"']+\.apk[^"']*)["']""", RegexOption.IGNORE_CASE)

        /**
         * Apache autoindex version-dir regex.
         *
         * Matches BOTH styles seen in the wild:
         *   - relative:  href="1.10.0/"      (Play! autoindex)
         *   - absolute:  href="/stable/1.21.0/"  (RetroArch buildbot/h5ai)
         *
         * Captures only the version segment ("1.10.0", "1.21.0"). The
         * caller reconstructs the absolute URL using its own base.
         */
        private val VERSION_DIR_REGEX =
            Regex("""href\s*=\s*["'](?:[^"']*?/)?(\d[\d.]*)/["']""",
                RegexOption.IGNORE_CASE)

        /** Architecture subdir name (arm64-v8a/, etc.). Same
         *  relative-or-absolute href tolerance as the version regex. */
        private val ARCH_DIR_REGEX =
            Regex("""href\s*=\s*["'](?:[^"']*?/)?((?:arm64-v8a|arm64|aarch64|armeabi-v7a|armeabi|x86_64|x86))/["']""",
                RegexOption.IGNORE_CASE)

        /** Any absolute URL ending in .apk on a dolphin-emu.org CDN. */
        private val DOLPHIN_APK_URL_REGEX =
            Regex("""https?://[^"'\s<>]+\.apk[^"'\s<>]*""", RegexOption.IGNORE_CASE)
    }
}
