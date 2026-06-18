package io.github.mayusi.emutran.domain.drivers

import io.github.mayusi.emutran.data.device.GpuFamily
import io.github.mayusi.emutran.data.device.GpuInfo
import io.github.mayusi.emutran.data.source.GhAsset
import io.github.mayusi.emutran.data.source.GhRelease
import io.github.mayusi.emutran.data.source.HttpCache
import io.github.mayusi.emutran.data.source.HttpsDowngradeInterceptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Downloads custom GPU driver zips from K11MCH1/AdrenoToolsDrivers and
 * stages them in the user's Emulation/tools/turnip/ folder.
 *
 * Driver matching IS partly keyed by GPU: the Qualcomm official builds
 * are series-specific. Their asset-name "number" is a driver version
 * that maps to an Adreno series, not the family revision:
 *   - Qualcomm_840 / Qualcomm_837  -> Adreno 7xx
 *   - Qualcomm_849                 -> Adreno 8xx
 *   - 8Elite2-*                    -> Snapdragon 8 Elite 2
 * These builds are spread across DIFFERENT GitHub releases (each tag
 * tends to ship one Qualcomm build plus some Turnip variants), so just
 * fetching /releases/latest misses the right Qualcomm driver for most
 * GPUs.
 *
 * The Turnip (open-source Mesa) builds — Turnip_v*.zip and their
 * _Gmem/_Sysmem variants — are universal across Adreno, with an
 * occasional series-specific extra (turnip_a8xx for 8xx).
 *
 * So discover() now: fetches recent releases, computes the GPU series
 * from the detected Adreno model, and returns a CURATED asset set =
 * the matching Qualcomm driver (when one exists for the series) plus
 * the newest universal Turnip variant set. If the model is unknown we
 * fall back to just the latest universal Turnip set. A README in the
 * folder explains the difference and records what was selected.
 *
 * If the device isn't Adreno, we skip entirely (Turnip is Adreno-only).
 *
 * HTTPS-downgrade protection: driver zips are downloaded via a private
 * client that rejects any redirect from https:// to http://, matching
 * the protection applied to APK downloads in ApkDownloader.
 */
@Singleton
class DriverStager @Inject constructor(
    // Shared singleton OkHttpClient provided by AppModule. A private client
    // is built from it (see [downloadClient]) that adds the HTTPS-downgrade
    // interceptor without mutating the shared pool.
    private val client: OkHttpClient,
    // Shared kotlinx.serialization Json (ignoreUnknownKeys = true) from AppModule.
    private val json: Json,
    // FIX 10: ETag/body cache so discover() doesn't burn a GitHub rate-limit token
    // on every invocation. Same HttpCache used by GitHubReleasesSource / GiteaSource.
    private val cache: HttpCache,
) {

    /**
     * Private download client: inherits the shared client's pool, timeouts,
     * and GitHub auth interceptor, but adds an interceptor that blocks any
     * HTTPS→HTTP redirect. Defense-in-depth: driver zips transit the same
     * GitHub CDN as APKs, so downgrade protection is equally warranted.
     */
    private val downloadClient: OkHttpClient = client.newBuilder()
        .addNetworkInterceptor(HttpsDowngradeInterceptor())
        .build()

    /**
     * Discover the right driver set for [gpu]. Fetches recent releases,
     * matches the Qualcomm build to the detected Adreno series, and
     * curates that together with the newest universal Turnip set.
     */
    suspend fun discover(gpu: GpuInfo): DiscoverResult = withContext(Dispatchers.IO) {
        if (gpu.family != GpuFamily.ADRENO) {
            return@withContext DiscoverResult.NotApplicable(
                "GPU is ${gpu.family}, custom Turnip drivers are Adreno-only."
            )
        }

        try {
            // FIX 10: send If-None-Match if we've cached this endpoint before. A 304
            // costs no rate-limit token, so repeated discover() calls stay near-free.
            val cached = cache.get(RELEASES_ENDPOINT)
            val req = Request.Builder()
                .url(RELEASES_ENDPOINT)
                .header("Accept", "application/vnd.github+json")
                .apply { cached?.let { header("If-None-Match", it.etag) } }
                .build()
            client.newCall(req).execute().use { resp ->
                // 304 Not Modified → reuse the cached body, no token spent.
                val body: String = if (resp.code == 304 && cached != null) {
                    cached.body
                } else {
                    if (!resp.isSuccessful) {
                        return@withContext DiscoverResult.Failed("GitHub HTTP ${resp.code}")
                    }
                    val fresh = resp.body?.string()
                        ?: return@withContext DiscoverResult.Failed("Empty response")
                    // Cache the ETag for future If-None-Match requests.
                    resp.header("ETag")?.let { etag ->
                        cache.put(RELEASES_ENDPOINT, HttpCache.Entry(etag, fresh, System.currentTimeMillis()))
                    }
                    fresh
                }
                val releases: List<GhRelease> = json.decodeFromString(body)
                val selection = selectAssetsForGpu(releases, gpu)
                if (selection.assets.isEmpty()) {
                    return@withContext DiscoverResult.Failed(
                        "No compatible drivers found for Adreno " +
                            "${gpu.adrenoModel ?: "(unknown model)"} across ${releases.size} releases."
                    )
                }
                DiscoverResult.Found(
                    releaseTag = selection.releaseTag,
                    releaseName = selection.releaseName,
                    assets = selection.assets,
                )
            }
        } catch (t: Throwable) {
            DiscoverResult.Failed(t.message ?: t.javaClass.simpleName)
        }
    }

    /** Adreno series buckets we match Qualcomm builds against. */
    private enum class GpuSeries { SERIES_7XX, SERIES_8XX, ELITE, UNKNOWN }

    private fun seriesFor(gpu: GpuInfo): GpuSeries {
        val renderer = gpu.renderer.lowercase()
        // Snapdragon 8 Elite (and Elite 2) report distinctive renderer
        // strings; be tolerant since they vary across ROMs.
        if ("8 elite" in renderer || "8elite" in renderer || "8gen elite" in renderer) {
            return GpuSeries.ELITE
        }
        return when (gpu.adrenoModel) {
            in 700..799 -> GpuSeries.SERIES_7XX
            in 800..899 -> GpuSeries.SERIES_8XX
            else -> GpuSeries.UNKNOWN
        }
    }

    /**
     * Pure selection logic over already-fetched [releases]. Curates the
     * matching Qualcomm driver (by series) plus the newest universal
     * Turnip variant set. Visible for testing.
     */
    internal fun selectAssetsForGpu(releases: List<GhRelease>, gpu: GpuInfo): Selection {
        val series = seriesFor(gpu)

        // Flatten all assets with their owning release, preserving the
        // API's newest-first ordering.
        data class Owned(val release: GhRelease, val asset: GhAsset)
        val owned = releases.flatMap { rel -> rel.assets.map { Owned(rel, it) } }

        fun GhAsset.token() = name.lowercase().replace('-', '_')

        // --- Qualcomm match for the series (newest-first wins ties) ---
        val qualcomm: Owned? = when (series) {
            GpuSeries.ELITE -> owned.firstOrNull { "8elite2" in it.asset.token() }
            GpuSeries.SERIES_8XX -> owned.firstOrNull {
                QUALCOMM_849_REGEX.containsMatchIn(it.asset.token())
            }
            GpuSeries.SERIES_7XX -> {
                // Prefer the higher/newer Qualcomm number (840 over 837).
                val sevenXx = owned.filter {
                    QUALCOMM_8XX_REGEX.containsMatchIn(it.asset.token()) &&
                        ("qualcomm_840" in it.asset.token() || "qualcomm_837" in it.asset.token())
                }
                sevenXx.maxByOrNull { o ->
                    QUALCOMM_NUMBER_REGEX.find(o.asset.token())
                        ?.groupValues?.get(1)?.toIntOrNull() ?: 0
                }
            }
            GpuSeries.UNKNOWN -> null
        }

        // --- Newest universal Turnip set ---
        // Find the newest release that ships Turnip_v* assets, then take
        // its full variant set (base + _Gmem/_Sysmem/etc).
        val turnipRelease: GhRelease? = releases.firstOrNull { rel ->
            rel.assets.any { it.name.lowercase().startsWith("turnip_v") }
        }
        val turnipAssets: List<GhAsset> = turnipRelease?.assets?.filter {
            val n = it.name.lowercase()
            n.startsWith("turnip_v") || n.startsWith("turnip_a")
        }.orEmpty()

        val curated = mutableListOf<Asset>()
        qualcomm?.let { curated += it.asset.toAsset() }
        turnipAssets.forEach { curated += it.toAsset() }

        // Release tag for display: prefer the Qualcomm release (it's the
        // GPU-specific pick); else the Turnip release.
        val tagOwner = qualcomm?.release ?: turnipRelease
        return Selection(
            releaseTag = tagOwner?.tagName.orEmpty(),
            releaseName = tagOwner?.name.orEmpty(),
            assets = curated.distinctBy { it.name },
        )
    }

    private fun GhAsset.toAsset() = Asset(name, browserDownloadUrl, size)

    /** Result of [selectAssetsForGpu]. */
    internal data class Selection(
        val releaseTag: String,
        val releaseName: String,
        val assets: List<Asset>,
    )

    /**
     * Download [picked] assets into [turnipDir] (typically
     * Emulation/tools/turnip/). Existing files with matching names are
     * overwritten — idempotent.
     *
     * Uses [downloadClient] (HTTPS-downgrade protected) for all fetches.
     */
    suspend fun stage(picked: List<Asset>, turnipDir: File): StageResult = withContext(Dispatchers.IO) {
        if (!turnipDir.exists() && !turnipDir.mkdirs()) {
            return@withContext StageResult.Failed("Could not create ${turnipDir.absolutePath}")
        }
        // Driver-zip integrity note:
        // K11MCH1/AdrenoToolsDrivers releases do not publish a .sha256 sidecar
        // asset alongside the driver zips, so we cannot do a hash check here.
        // Download integrity relies on GitHub TLS (certificate-pinned by the OS
        // trust store) and GitHub's own content-hash verification on their CDN.
        // If a sidecar pattern is added in future (e.g. <name>.sha256), add a
        // best-effort fetch + verify step here: fetch the small text file, extract
        // the first 64-hex token, compute SHA-256 of the downloaded bytes, and
        // reject the zip on mismatch.
        val saved = mutableListOf<File>()
        val failed = mutableListOf<Pair<String, String>>()
        for (asset in picked) {
            when (val r = downloadAsset(asset, turnipDir)) {
                is AssetDownload.Ok -> saved += r.file
                is AssetDownload.Err -> failed += asset.name to r.reason
            }
        }

        // Drop a README that explains the variants so the user knows
        // which one to load in which emulator.
        runCatching {
            File(turnipDir, "README.txt").writeText(readmeBody(saved.map { it.name }))
        }

        StageResult.Done(saved = saved.map { it.name }, failed = failed)
    }

    /**
     * FIX 2: Download a single [asset] into [turnipDir] safely.
     *
     * Writes to a "<name>.tmp" sidecar and only atomically renames it onto the
     * final name after a fully successful body copy. A disk-full / dropped-socket
     * failure mid-write therefore leaves only the temp file (which we delete),
     * never a half-written zip that the driver health check would falsely PASS.
     *
     * Transient failures (non-success HTTP, empty body, IOException) are retried
     * with a short exponential backoff, mirroring [ApkDownloader]'s retry shape.
     */
    private suspend fun downloadAsset(asset: Asset, turnipDir: File): AssetDownload {
        val out = File(turnipDir, asset.name)
        val tmp = File(turnipDir, asset.name + ".tmp")
        var lastReason = "Unknown error"

        for (attempt in 1..MAX_ATTEMPTS) {
            // Start each attempt from a clean temp file.
            tmp.delete()
            try {
                // Use downloadClient (HTTPS-downgrade interceptor) for all fetches.
                downloadClient.newCall(Request.Builder().url(asset.url).build()).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        lastReason = "HTTP ${resp.code}"
                        return@use
                    }
                    val body = resp.body ?: run {
                        lastReason = "Empty body"
                        return@use
                    }
                    body.byteStream().use { input ->
                        tmp.outputStream().use { o -> input.copyTo(o) }
                    }
                    // Atomic publish: rename succeeds only after the full body landed.
                    // Delete any stale final file first so rename can't fail on Windows-y FS.
                    out.delete()
                    if (!tmp.renameTo(out)) {
                        // Fall back to copy+delete if rename across the same dir fails.
                        tmp.copyTo(out, overwrite = true)
                        tmp.delete()
                    }
                    return AssetDownload.Ok(out)
                }
            } catch (t: Throwable) {
                lastReason = t.message ?: t.javaClass.simpleName
            }

            // Reached here = transient failure this attempt. Clean the partial temp.
            tmp.delete()
            if (attempt < MAX_ATTEMPTS) {
                delay(BASE_RETRY_DELAY_MS * (1L shl (attempt - 1)))  // 0.5s, 1s
            }
        }
        return AssetDownload.Err(lastReason)
    }

    private sealed interface AssetDownload {
        data class Ok(val file: File) : AssetDownload
        data class Err(val reason: String) : AssetDownload
    }

    private fun readmeBody(savedFilenames: List<String>): String = buildString {
        appendLine("=== Custom GPU drivers ===")
        appendLine()
        appendLine("EmuTran staged the following driver zips for you:")
        savedFilenames.forEach { appendLine("  - $it") }
        appendLine()
        appendLine("These are custom Adreno GPU drivers from")
        appendLine("https://github.com/K11MCH1/AdrenoToolsDrivers")
        appendLine()
        appendLine("EmuTran selected the Qualcomm build matching your")
        appendLine("Adreno series automatically, plus the latest universal")
        appendLine("Turnip variants. (Qualcomm_840/837 = Adreno 7xx,")
        appendLine("Qualcomm_849 = Adreno 8xx, 8Elite2 = Snapdragon 8 Elite 2.)")
        appendLine()
        appendLine("How to use:")
        appendLine("  - Dolphin: Graphics Settings -> Vulkan -> Load Custom Driver")
        appendLine("  - Eden / Yuzu / Sudachi: Settings -> GPU -> Use custom driver")
        appendLine("  - Skyline / Strato: Settings -> GPU Driver -> Select zip")
        appendLine()
        appendLine("Which one to pick:")
        appendLine("  - Qualcomm_*_adpkg.zip = official Qualcomm Vulkan driver")
        appendLine("    (most compatible, sometimes faster on stock games)")
        appendLine("  - Turnip_v*.zip = open-source Mesa driver")
        appendLine("    (often faster for Switch/PS Vita emulation;")
        appendLine("     try _Gmem or _Sysmem variants for tuning)")
        appendLine()
        appendLine("Try the Qualcomm driver first, then experiment with Turnip")
        appendLine("variants if you want more performance in specific emulators.")
    }

    data class Asset(val name: String, val url: String, val sizeBytes: Long)

    sealed interface DiscoverResult {
        data class Found(
            val releaseTag: String,
            val releaseName: String,
            val assets: List<Asset>,
        ) : DiscoverResult
        data class NotApplicable(val reason: String) : DiscoverResult
        data class Failed(val reason: String) : DiscoverResult
    }

    sealed interface StageResult {
        data class Done(
            val saved: List<String>,
            val failed: List<Pair<String, String>>,
        ) : StageResult
        data class Failed(val reason: String) : StageResult
    }

    companion object {
        // FIX 2: atomic-download retry budget. 1 initial + 2 retries with a short
        // backoff (0.5s, 1s) — mirrors ApkDownloader's transient-retry shape but
        // smaller since driver staging is a background convenience, not a blocker.
        private const val MAX_ATTEMPTS = 3
        private const val BASE_RETRY_DELAY_MS = 500L

        // FIX 10: the AdrenoToolsDrivers releases endpoint, routed through HttpCache
        // with an ETag so repeated discover() calls cost no rate-limit token on 304.
        private const val RELEASES_ENDPOINT =
            "https://api.github.com/repos/K11MCH1/AdrenoToolsDrivers/releases?per_page=30"

        // Hoisted so they compile once instead of on every selectAssetsForGpu
        // call. Matched against the lowercased, '-'→'_' normalised asset name.

        /** Adreno 8xx Qualcomm build marker. */
        private val QUALCOMM_849_REGEX = Regex("""qualcomm_849""")

        /** Any qualcomm_8xx build (gate for the 7xx 840/837 filter). */
        private val QUALCOMM_8XX_REGEX = Regex("""qualcomm_8[0-9]{2}""")

        /** Captures the 3-digit Qualcomm number so 840 beats 837 in ties. */
        private val QUALCOMM_NUMBER_REGEX = Regex("""qualcomm_(\d{3})""")
    }
}
