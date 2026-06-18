package io.github.mayusi.emutran.data.manifest

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.mayusi.emutran.data.source.HttpCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the Obtainium pack JSON and turns it into a clean List<AppEntry>.
 *
 * Two manifests ship in the APK as fallback assets:
 *   - obtainium-emulation-pack-latest.json            (standard / single-screen)
 *   - obtainium-emulation-pack-dual-screen-latest.json
 *
 * Caller picks one based on the device's screen-type setting.
 *
 * == Auto-refresh from GitHub ==
 *
 * On each [loadStandard]/[loadDualScreen] call (first call per process)
 * this class attempts to fetch the latest manifest JSON from the upstream
 * RJNY/Obtainium-Emulation-Pack repository on GitHub:
 *
 *   Standard:     https://raw.githubusercontent.com/RJNY/Obtainium-Emulation-Pack/main/obtainium-emulation-pack-latest.json
 *   Dual-screen:  https://raw.githubusercontent.com/RJNY/Obtainium-Emulation-Pack/main/obtainium-emulation-pack-dual-screen-latest.json
 *
 * ETag-based revalidation via [HttpCache] is used so repeated calls
 * across sessions are cheap (304 when nothing changed). A successfully
 * fetched JSON is also written to [Context.filesDir]/manifest/ so the
 * refreshed copy survives process death and works fully offline on
 * subsequent launches.
 *
 * == Fallback chain ==
 *
 *   1. Network fetch (with ETag / 304 revalidation).
 *   2. On failure / offline → disk-persisted copy from a prior successful fetch.
 *   3. No disk copy → bundled asset baked into the APK.
 *
 * The app works fully offline from first launch (step 3 always works).
 *
 * == In-memory parse cache ==
 *
 * Parsed List<AppEntry> is cached per variant for the lifetime of the
 * process. Repeated calls to [loadStandard]/[loadDualScreen] return the
 * cached list without re-parsing or re-fetching. The cache is invalidated
 * only on a successful network refresh that returned new content (i.e.,
 * the 304 path re-uses the existing parsed result as-is).
 */
@Singleton
class ObtainiumPackParser @Inject constructor(
    @ApplicationContext private val context: Context,
    private val httpCache: HttpCache,
    private val okHttpClient: OkHttpClient,
    private val manifestDiffStore: ManifestDiffStore,
) {
    private val json = Json {
        ignoreUnknownKeys = true   // future Obtainium settings shouldn't break us
        isLenient = true
    }

    // Per-variant in-memory parse cache. Null means "not yet loaded this process."
    @Volatile private var standardCache: List<AppEntry>? = null
    @Volatile private var dualScreenCache: List<AppEntry>? = null

    // Mutex prevents two coroutines from double-fetching the same variant
    // concurrently at startup (e.g. PickAppsViewModel + ProgressViewModel
    // both init before the first result is cached).
    private val standardMutex = Mutex()
    private val dualScreenMutex = Mutex()

    suspend fun loadStandard(): List<AppEntry> =
        standardMutex.withLock {
            standardCache ?: load(
                STANDARD_FILENAME, STANDARD_URL, ManifestDiffStore.VARIANT_STANDARD,
            ).also { standardCache = it }
        }

    suspend fun loadDualScreen(): List<AppEntry> =
        dualScreenMutex.withLock {
            dualScreenCache
                ?: load(
                    DUAL_SCREEN_FILENAME, DUAL_SCREEN_URL, ManifestDiffStore.VARIANT_DUAL_SCREEN,
                ).also { dualScreenCache = it }
        }

    /**
     * Parses ONLY the bundled APK asset — no network fetch, no disk cache,
     * no coroutine mutex. Runs on IO but is cheap (asset read + JSON parse).
     *
     * Intended for startup routing (SetupStateDetector.isSetUp) where we need
     * a fast, offline-capable answer to "is any manifest app installed?" before
     * deciding whether to show the Dashboard or the Setup splash. The live
     * network refresh still happens lazily when the user reaches PickApps /
     * Dashboard (via loadStandard / loadDualScreen — unchanged).
     *
     * This method deliberately does NOT populate standardCache so that the
     * first loadStandard() call still triggers a network refresh in the
     * background, picking up any updates since the APK was built.
     */
    suspend fun loadBundledOnly(): List<AppEntry> = withContext(Dispatchers.IO) {
        parseAsset(STANDARD_FILENAME)
    }

    /**
     * Load one manifest variant via the full fallback chain:
     *   network → disk cache → bundled asset.
     *
     * Returns the parsed list. Never throws — worst case returns the
     * bundled asset entries.
     */
    private suspend fun load(filename: String, remoteUrl: String, variant: String): List<AppEntry> =
        withContext(Dispatchers.IO) {
            // --- Network attempt (with ETag revalidation) ---
            val networkResult =
                runCatching { fetchFromNetwork(filename, remoteUrl, variant) }.getOrNull()
            if (networkResult != null) return@withContext networkResult

            // --- Disk copy from a prior successful fetch ---
            val diskResult = runCatching { readFromDisk(filename) }.getOrNull()
            if (diskResult != null) return@withContext diskResult

            // --- Final fallback: bundled asset ---
            parseAsset(filename)
        }

    /**
     * Attempt a network fetch of [remoteUrl], using ETag from [httpCache]
     * if available. On 304 returns the previously-parsed list from the
     * in-memory cache if still warm (avoids re-parse), or re-parses the
     * cached body. On 200 persists the new body to disk and updates the
     * ETag. Returns null on any network/HTTP error.
     */
    private suspend fun fetchFromNetwork(
        filename: String,
        remoteUrl: String,
        variant: String,
    ): List<AppEntry>? {
        val cached = httpCache.get(remoteUrl, HttpCache.MANIFEST_TTL_MS)
        val request = Request.Builder()
            .url(remoteUrl)
            .header("Accept", "application/json")
            .apply { cached?.let { header("If-None-Match", it.etag) } }
            .build()

        return okHttpClient.newCall(request).execute().use { response ->
            when {
                response.code == 304 && cached != null -> {
                    // Nothing changed — re-use the cached body (already on disk).
                    // We return null here to let the caller fall through to disk,
                    // which avoids re-parsing and respects the already-warm
                    // in-process parse cache check in loadStandard/loadDualScreen.
                    // Actually, we parse from the cached body so the fallback chain
                    // doesn't need a separate disk read when we have the body.
                    parseJson(cached.body)
                }
                response.isSuccessful -> {
                    val body = response.body?.string() ?: return@use null
                    // Persist to disk immediately so future offline launches work.
                    persistToDisk(filename, body)
                    // Update ETag cache.
                    val etag = response.header("ETag") ?: ""
                    httpCache.put(remoteUrl, HttpCache.Entry(etag, body, System.currentTimeMillis()))
                    val entries = parseJson(body)
                    // Capture a "what's new" diff vs the previously-seen catalog so
                    // the dashboard can show a "Pack updated: +X, -Y" banner. Only on
                    // a fresh 200 (a genuine upstream change); 304 / disk / asset paths
                    // never call this. Exclude the Obtainium meta-entry — it has no
                    // real package and shouldn't surface in the banner.
                    captureManifestDiff(variant, entries)
                    entries
                }
                else -> null  // 4xx/5xx — fall through to disk/bundled
            }
        }
    }

    /**
     * Feed the freshly-parsed [entries] to [ManifestDiffStore] so it can
     * capture an added/removed diff vs the previously-seen catalog FOR THE
     * SAME [variant] (standard vs dual-screen). Compares by id (excluding
     * [OBTAINIUM_META_ENTRY_ID]); removed ids that are no longer in the catalog
     * fall back to the id string for their name.
     *
     * Passing [variant] keeps the baseline per-variant so refreshing both
     * manifests in one session can't fabricate a bogus cross-variant banner.
     *
     * Wrapped in runCatching so a diff-store hiccup can never break the
     * manifest fetch (the fetch result is already computed by this point).
     */
    private suspend fun captureManifestDiff(variant: String, entries: List<AppEntry>) {
        runCatching {
            val visible = entries.filterNot { it.id == OBTAINIUM_META_ENTRY_ID }
            val currentIds = visible.map { it.id }.toSet()
            val nameById = visible.associate { it.id to it.name }
            manifestDiffStore.computeAndStoreDiff(variant, currentIds) { id -> nameById[id] ?: id }
        }
    }

    /** Read a previously-persisted manifest from [Context.filesDir]/manifest/. */
    private fun readFromDisk(filename: String): List<AppEntry>? {
        val file = diskFile(filename)
        if (!file.exists()) return null
        val text = file.readText()
        return parseJson(text)
    }

    /**
     * Write [body] to [Context.filesDir]/manifest/[filename] atomically.
     *
     * A plain writeText() truncates-then-writes in place, so a process kill
     * mid-write leaves a half-written (corrupt) manifest on disk that the
     * offline fallback chain would then fail to parse forever. Instead we write
     * the full body to a sibling "<filename>.tmp" and only [File.renameTo] it
     * over the final path once the write fully succeeded — a rename is atomic on
     * the same filesystem, so a reader ever only sees the old or new complete
     * file. The tmp file is cleaned up if the write or rename fails.
     */
    private fun persistToDisk(filename: String, body: String) {
        val file = diskFile(filename)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "$filename.tmp")
        try {
            tmp.writeText(body)
            if (!tmp.renameTo(file)) {
                // renameTo can fail (e.g. across mounts); fall back to a direct
                // write so the refreshed copy is at least persisted, then clean up.
                file.writeText(body)
                tmp.delete()
            }
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }
    }

    private fun diskFile(filename: String): File =
        File(context.filesDir, "manifest/$filename")

    /** Read and parse the bundled APK asset. Always available. */
    private fun parseAsset(filename: String): List<AppEntry> {
        val rawText = context.assets.open("manifest/$filename")
            .bufferedReader().use { it.readText() }
        return parseJson(rawText)
    }

    /**
     * Pure function: take raw JSON text → typed AppEntry list.
     * Exposed (internal) so unit tests can exercise the full mapping
     * without an Android Context.
     */
    internal fun parseJson(rawText: String): List<AppEntry> {
        val pack = json.decodeFromString(ObtainiumPackJson.serializer(), rawText)
        return pack.apps.mapNotNull { raw ->
            val settings = runCatching {
                json.decodeFromString(AppAdditionalSettings.serializer(), raw.additionalSettings)
            }.getOrNull() ?: AppAdditionalSettings()
            toAppEntry(raw, settings)
        }
    }

    /**
     * Map one raw Obtainium entry to our flattened AppEntry.
     * Returns null only if the entry is so malformed we can't use it.
     */
    private fun toAppEntry(raw: RawAppEntry, settings: AppAdditionalSettings): AppEntry? {
        // Canonicalize the id first. A few entries (HTML-scrape sources like
        // RetroArch) carry an Obtainium *numeric tracker id* instead of a
        // real Android package name. We map those to the real package name
        // so installed-detection (which compares against PackageManager
        // package names) and recommended-matching work consistently.
        val id = ID_OVERRIDES[raw.id] ?: raw.id
        val source = classifySource(raw.url, raw.overrideSource)
        val system = systemFor(id, raw.name)
        val recommended = system in RECOMMENDED_SYSTEMS &&
            id in RECOMMENDED_IDS &&
            !settings.trackOnly
        val exclusiveGroup = MUTUALLY_EXCLUSIVE_GROUPS[id]

        return AppEntry(
            id = id,
            name = displayNameFor(id, raw.name),
            author = raw.author,
            about = settings.about,
            sourceUrl = raw.url,
            source = source,
            apkFilterRegEx = settings.apkFilterRegEx,
            invertApkFilter = settings.invertAPKFilter,
            autoFilterByArch = settings.autoApkFilterByArch,
            includePrereleases = settings.includePrereleases,
            fallbackToOlderReleases = settings.fallbackToOlderReleases,
            versionExtractionRegEx = settings.versionExtractionRegEx,
            filterReleaseTitlesRegEx = settings.filterReleaseTitlesByRegEx,
            categories = raw.categories,
            trackOnly = settings.trackOnly,
            system = system,
            recommended = recommended,
            mutuallyExclusiveGroup = exclusiveGroup,
        )
    }

    /**
     * Friendly display-name override for specific ids whose raw manifest
     * names collide or read confusingly. Returns the override when present,
     * otherwise the raw name unchanged. Data-driven so future renames are
     * a one-line addition to [DISPLAY_NAME_OVERRIDES].
     *
     * Motivating case: three Winlator forks ship under distinct package ids
     * but two of them render as "Winlator"/"winlator", which is ambiguous.
     */
    private fun displayNameFor(id: String, rawName: String): String =
        DISPLAY_NAME_OVERRIDES[id] ?: rawName

    /**
     * URL pattern → which AppSource to use later.
     * overrideSource on the raw entry wins when present (Obtainium uses it
     * to disambiguate cases where the URL alone is ambiguous).
     */
    private fun classifySource(url: String, override: String?): SourceKind {
        override?.let {
            return when (it.lowercase()) {
                "github" -> SourceKind.GITHUB
                "gitea", "codeberg" -> SourceKind.GITEA
                "html" -> SourceKind.HTML_SCRAPE
                else -> SourceKind.UNKNOWN
            }
        }
        return when {
            "github.com" in url -> SourceKind.GITHUB
            "git.eden-emu.dev" in url || "codeberg.org" in url -> SourceKind.GITEA
            url.startsWith("http") -> SourceKind.HTML_SCRAPE
            else -> SourceKind.UNKNOWN
        }
    }

    /**
     * Heuristic system tagging by id/name. Hand-curated rather than
     * machine-inferred because the manifest doesn't include this info
     * and we want predictable grouping in the picker UI.
     */
    private fun systemFor(id: String, name: String): SystemTag {
        val key = (id + " " + name).lowercase()
        return when {
            // Drivers: specific repos that ship GPU drivers as .zip
            // releases. Be precise — 'turnip' alone matches NetherSX2-Turnip
            // (an emulator that happens to use the word), so we anchor on
            // the repo-name patterns instead.
            "adreno-tools" in key || "purple-turnip" in key ||
                "purple_turnip" in key || "purple turnip" in key ||
                "driver" in key -> SystemTag.DRIVERS

            // Frontends and launchers
            "es-de" in key || "esde" in key || "daijish" in key ||
                "pegasus" in key || "cocoon" in key || "gamehub" in key ||
                "gamenative" in key -> SystemTag.FRONTEND

            // Utilities
            "moonlight" in key || "syncthing" in key || "artemis" in key ||
                "ra-offline" in key || "raoffline" in key || "odintools" in key ||
                "obtainium" in key -> SystemTag.UTILITY

            // Multi-system retro
            "retroarch" in key -> SystemTag.RETRO

            // Nintendo handhelds
            "azahar" in key || "citra" in key || "melonds" in key ||
                "lime" in key || "panda3ds" in key -> SystemTag.NINTENDO_HANDHELD

            // Nintendo consoles
            "dolphin" in key || "cemu" in key || "eden" in key ||
                "citron" in key || "yuzu" in key || "ryujinx" in key ||
                "skyline" in key || "sudachi" in key -> SystemTag.NINTENDO_CONSOLE

            // PlayStation family
            "duckstation" in key || "nethersx2" in key || "armsx2" in key ||
                "aps3e" in key || "rpcsx" in key || "vita3k" in key ||
                "ppsspp" in key || "play" in key -> SystemTag.PLAYSTATION

            // Sega
            "flycast" in key || "redream" in key -> SystemTag.SEGA

            // PC / Windows-on-Android
            "winlator" in key || "wine" in key || "scummvm" in key -> SystemTag.PC_WINDOWS

            else -> SystemTag.OTHER
        }
    }

    companion object {
        const val STANDARD_FILENAME = "obtainium-emulation-pack-latest.json"
        const val DUAL_SCREEN_FILENAME = "obtainium-emulation-pack-dual-screen-latest.json"

        /**
         * Obtainium numeric tracker id for the Obtainium pack meta-entry itself.
         * This entry has no real Android package id and must be excluded from
         * any picker / dashboard app lists.
         *
         * Currently filtered inline in PickAppsViewModel, QuickSetupViewModel, and
         * DashboardViewModel as `filterNot { it.id == OBTAINIUM_META_ENTRY_ID }`.
         * Those call-sites should reference this constant instead of the raw string.
         */
        const val OBTAINIUM_META_ENTRY_ID = "904332840"

        /**
         * Raw GitHub URLs for RJNY/Obtainium-Emulation-Pack (main branch).
         * Filenames match the bundled asset names so the fallback chain
         * always uses the same filename key.
         */
        private const val GITHUB_RAW_BASE =
            "https://raw.githubusercontent.com/RJNY/Obtainium-Emulation-Pack/main"
        const val STANDARD_URL = "$GITHUB_RAW_BASE/$STANDARD_FILENAME"
        const val DUAL_SCREEN_URL = "$GITHUB_RAW_BASE/$DUAL_SCREEN_FILENAME"

        /**
         * Raw manifest id → real Android package name. Some entries use an
         * Obtainium *numeric tracker id* because their source is an
         * HTML-scrape page (no GitHub package coordinates). A numeric id can
         * never match a real installed package name from PackageManager, so
         * RetroArch's installed-detection and "Recommended" pre-check would
         * be broken forever. We rewrite the id to the real package name at
         * parse time so the rest of the app keys off something matchable.
         *
         * 487343354 = RetroArch (AArch64); real package = com.retroarch.aarch64.
         */
        private val ID_OVERRIDES: Map<String, String> = mapOf(
            "487343354" to "com.retroarch.aarch64",
        )

        /**
         * id → friendly display name. Used to disambiguate entries whose
         * raw manifest names collide or read confusingly. The three Winlator
         * forks have different package ids (so they can coexist) but two of
         * them otherwise render as the same string — these overrides give
         * each card a distinct label. Add more entries here as needed.
         */
        private val DISPLAY_NAME_OVERRIDES: Map<String, String> = mapOf(
            "com.winlator" to "Winlator",
            "com.winlator.cmod" to "Winlator (Community Mod)",
            "com.winlator.ludashi" to "Winlator (Ludashi)",
        )

        /**
         * Systems whose entries get pre-checked in the picker.
         * Drivers and PC_WINDOWS stay opt-in because not everyone needs them.
         */
        private val RECOMMENDED_SYSTEMS = setOf(
            SystemTag.RETRO,
            SystemTag.NINTENDO_HANDHELD,
            SystemTag.NINTENDO_CONSOLE,
            SystemTag.PLAYSTATION,
            SystemTag.SEGA,
        )

        /**
         * Within those systems, a curated id-allowlist of the "best
         * default" per category so we don't pre-check every alternative.
         * IDs are the *real* Android package IDs from the manifest — not
         * guesses. Tuned for the Odin 3 (Adreno 740, plenty of horsepower).
         */
        private val RECOMMENDED_IDS = setOf(
            "aenu.aps3e",                          // aPS3e (PS3)
            "org.azahar_emu.azahar",               // Azahar (3DS, stable)
            "info.cemu.cemu",                      // Cemu (Wii U)
            "com.flycast.emulator",                // Flycast (Dreamcast)
            "me.magnum.melonds",                   // MelonDS (DS)
            "xyz.aethersx2.android",               // NetherSX2 (PS2, default)
            "org.dolphinemu.dolphinemu",           // Dolphin (GC/Wii)
            "org.ppsspp.ppsspp",                   // PPSSPP
            "dev.eden.eden_emulator",              // Eden (Switch)
            "com.github.stenzek.duckstation",      // DuckStation (PS1)
            "com.retroarch.aarch64",               // RetroArch (AArch64) — real package (was tracker id 487343354)
            "org.scummvm.scummvm",                 // ScummVM
            "com.limelight",                       // Moonlight (LAN streaming)
        )

        /**
         * Apps whose Android packageId conflicts so Android can only have
         * one installed at a time. Picker shows them as a radio group.
         *
         * Map: appEntry.id → group key.
         *
         * Currently the only confirmed conflict in the standard pack is
         * Cemu's two-fork problem (info.cemu.cemu), which only manifests
         * when SapphireRhodonite's dual-screen fork is also installed.
         * MelonDS vs MelonDualDS use DIFFERENT package ids
         * (me.magnum.melonds vs me.magnum.melondualds) — they can coexist.
         * NetherSX2 vs NetherSX2-Turnip likewise use different ids
         * (xyz.aethersx2.android vs xyz.aethersx2.tturnip).
         *
         * Re-derive this map if/when more conflicting forks land in the
         * dual-screen manifest. Keeping it conservative for now.
         */
        private val MUTUALLY_EXCLUSIVE_GROUPS: Map<String, String> = emptyMap()
    }
}
