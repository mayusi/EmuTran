package io.github.mayusi.emutran.domain.health

import io.github.mayusi.emutran.data.device.GpuDetector
import io.github.mayusi.emutran.data.device.GpuFamily
import io.github.mayusi.emutran.data.device.InstalledAppsRegistry
import io.github.mayusi.emutran.data.device.NetworkChecker
import io.github.mayusi.emutran.data.manifest.AppEntry
import io.github.mayusi.emutran.data.manifest.ObtainiumPackParser
import io.github.mayusi.emutran.data.manifest.SystemTag
import io.github.mayusi.emutran.data.selection.SelectedAppsStore
import io.github.mayusi.emutran.data.setup.SetupStateDetector
import io.github.mayusi.emutran.data.source.AppSourceRouter
import io.github.mayusi.emutran.data.source.ResolveResult
import io.github.mayusi.emutran.data.storage.SetupOptionsStore
import io.github.mayusi.emutran.data.storage.StorageRootStore
import io.github.mayusi.emutran.domain.scaffold.FolderSpec
import io.github.mayusi.emutran.domain.scaffold.resolveEmulationRoot
import io.github.mayusi.emutran.domain.scaffold.resolveTurnipDir
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

// ─────────────────────────────────────────────────────────────────────────────
// Health check model types
// ─────────────────────────────────────────────────────────────────────────────

/** Overall outcome of a single health check. */
enum class HealthStatus { PASS, WARN, FAIL, SKIPPED }

/**
 * Result of one health check.
 *
 * @param id      Stable string identifier used as a list key.
 * @param title   Short noun-phrase label displayed on the card (e.g. "Emulation folder").
 * @param detail  One-line explanation of the outcome (pass reason or what's wrong).
 * @param status  PASS / WARN / FAIL / SKIPPED.
 */
data class HealthCheckResult(
    val id: String,
    val title: String,
    val detail: String,
    val status: HealthStatus,
)

// ─────────────────────────────────────────────────────────────────────────────
// HealthChecker
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Runs read-only checks against the user's emulation setup and returns a
 * list of [HealthCheckResult].  All checks are independent; a failure in
 * one does not prevent the rest from running.
 *
 * All IO is performed on [Dispatchers.IO] — safe to call from any coroutine.
 *
 * Checks (in display order):
 *  1. Emulation folder tree exists and is readable.
 *  2. BIOS sub-folders are present under Emulation/bios/.
 *  3. BIOS files present + hashes OK for systems that expect them.
 *  4. Selected emulators are still installed on the device.
 *  5. GPU driver .zip staged in tools/turnip/ (if user opted in).
 *  6. Catalog source resolution — verifies a bounded set of emulator
 *     entries can actually be resolved to download URLs so source rot is
 *     caught early rather than via user download failures.
 */
@Singleton
class HealthChecker @Inject constructor(
    private val storageRoot: StorageRootStore,
    private val selectedApps: SelectedAppsStore,
    private val installedApps: InstalledAppsRegistry,
    private val setupOptions: SetupOptionsStore,
    private val manifestParser: ObtainiumPackParser,
    private val gpuDetector: GpuDetector,
    private val networkChecker: NetworkChecker,
    private val sourceRouter: AppSourceRouter,
    private val biosValidator: BiosValidator,
    private val setupStateDetector: SetupStateDetector,
) {
    /**
     * Run all checks and return results in a stable display order.
     * Never throws — each check catches its own errors and returns FAIL.
     */
    suspend fun runChecks(): List<HealthCheckResult> = withContext(Dispatchers.IO) {
        val rootPath = storageRoot.rootPath.first()
        listOf(
            checkEmulationTree(rootPath),
            checkBiosFolders(rootPath),
            checkBiosFiles(rootPath),
            checkInstalledEmulators(),
            checkGpuDrivers(rootPath),
            checkCatalogResolution(),
        )
    }

    // ── Check 1: Emulation folder tree ───────────────────────────────────────

    /**
     * Verifies that the saved storage root exists on disk and contains a
     * recognisable Emulation/ sub-tree.
     *
     * Delegates the sentinel dir check to [SetupStateDetector.hasEmulationTree]
     * (now internal) so the canonical sentinel list ["roms","bios","saves","tools"]
     * lives in exactly one place and cannot drift between the two callers.
     *
     * A positive [hasEmulationTree] means at least one sentinel is present;
     * we also surface the readable path and found dirs for the detail message,
     * so the directory is re-resolved here for display purposes only.
     */
    private fun checkEmulationTree(rootPath: String?): HealthCheckResult {
        val id = "emulation_tree"
        val title = "Emulation folder"

        if (rootPath == null) {
            return HealthCheckResult(
                id = id,
                title = title,
                detail = "No storage root has been configured — run setup first.",
                status = HealthStatus.FAIL,
            )
        }

        return runCatching {
            val emulationDir = resolveEmulationRoot(rootPath)
            if (!emulationDir.isDirectory) {
                return@runCatching HealthCheckResult(
                    id = id,
                    title = title,
                    detail = "Emulation/ folder not found at ${emulationDir.absolutePath}.",
                    status = HealthStatus.FAIL,
                )
            }
            // Delegate sentinel presence check to SetupStateDetector (single source of truth).
            if (!setupStateDetector.hasEmulationTree(rootPath)) {
                HealthCheckResult(
                    id = id,
                    title = title,
                    detail = "Emulation/ exists but appears empty (no roms/bios/saves/tools).",
                    status = HealthStatus.WARN,
                )
            } else {
                // Enumerate which sentinels are present for the readable detail message.
                val sentinels = listOf("roms", "bios", "saves", "tools")
                val found = sentinels.filter { File(emulationDir, it).isDirectory }
                HealthCheckResult(
                    id = id,
                    title = title,
                    detail = "Readable at ${emulationDir.absolutePath} (${found.joinToString()}).",
                    status = HealthStatus.PASS,
                )
            }
        }.getOrElse { e ->
            HealthCheckResult(
                id = id,
                title = title,
                detail = "Could not read storage root: ${friendlyError(e)}",
                status = HealthStatus.FAIL,
            )
        }
    }

    // ── Check 2: BIOS sub-folders ────────────────────────────────────────────

    /**
     * Derives the expected BIOS sub-directory names from [FolderSpec.tree]
     * (every path matching "Emulation/bios/<system>") and checks how many
     * are present under the user's storage root.
     *
     * WARN when some but not all are present; PASS when all are present;
     * FAIL when the bios/ parent itself is missing.
     *
     * Note: we check folder existence only — actual BIOS file presence/content
     * is a future deep-scan feature.
     */
    private fun checkBiosFolders(rootPath: String?): HealthCheckResult {
        val id = "bios_folders"
        val title = "BIOS folders"

        if (rootPath == null) {
            return HealthCheckResult(
                id = id,
                title = title,
                detail = "No storage root configured — skipping BIOS check.",
                status = HealthStatus.SKIPPED,
            )
        }

        return runCatching {
            // Derive expected bios sub-dirs from FolderSpec (e.g. "Emulation/bios/ps1" → "ps1").
            val expectedSystems = FolderSpec.tree
                .filter { it.startsWith("Emulation/bios/") && it.count { c -> c == '/' } == 2 }
                .map { it.removePrefix("Emulation/bios/") }

            val emulationDir = resolveEmulationRoot(rootPath)
            val biosRoot = File(emulationDir, "bios")

            if (!biosRoot.isDirectory) {
                return@runCatching HealthCheckResult(
                    id = id,
                    title = title,
                    detail = "Emulation/bios/ folder is missing.",
                    status = HealthStatus.FAIL,
                )
            }

            val present = expectedSystems.count { File(biosRoot, it).isDirectory }
            val total = expectedSystems.size

            when {
                present == total -> HealthCheckResult(
                    id = id,
                    title = title,
                    detail = "All $total BIOS system folders are present.",
                    status = HealthStatus.PASS,
                )
                present == 0 -> HealthCheckResult(
                    id = id,
                    title = title,
                    detail = "BIOS system folders missing (0 of $total found).",
                    status = HealthStatus.WARN,
                )
                else -> HealthCheckResult(
                    id = id,
                    title = title,
                    detail = "$present of $total BIOS system folders present.",
                    status = HealthStatus.WARN,
                )
            }
        }.getOrElse { e ->
            HealthCheckResult(
                id = id,
                title = title,
                detail = "Could not check BIOS folders: ${friendlyError(e)}",
                status = HealthStatus.FAIL,
            )
        }
    }

    // ── Check 3: BIOS files ──────────────────────────────────────────────────

    /**
     * Deep-scans the actual BIOS *files* (not just folders) via [BiosValidator],
     * which reports per-file presence and — for files with a publicly documented
     * reference md5 — whether the user's file matches the known-good hash.
     *
     * Only systems that actually expect BIOS files participate; prose-only systems
     * (empty expected list in [FolderSpec.biosFilesBySystem]) are ignored so they
     * never count against the result.
     *
     * Intent filter (BIOS-1): the standard scaffold pre-creates *every* bios/<system>
     * folder, so a naïve scan screams about missing Dreamcast/PS2/Saturn BIOS at a
     * user who only emulates PS1. To avoid that false-positive wall, a system only
     * counts as "expected to be populated" when there is evidence the user set it up:
     *  - it already has at least one present file (a partial dump signals intent), OR
     *  - it maps (via [BIOS_SYSTEM_TAGS]) to a [SystemTag] the user selected an
     *    emulator for during setup.
     * Systems with zero present files that the user never tied to an emulator are
     * excluded from the "missing" problem list — we don't nag about BIOS the user
     * never intended to provide.
     *
     * Outcome:
     *  - SKIPPED — no storage root configured, no Emulation/bios tree to scan, or
     *              no *intended* system has a folder on disk yet.
     *  - PASS    — every expected file across the intended systems is present and
     *              no hash mismatch was found.
     *  - WARN    — some expected files are missing and/or a known hash is wrong;
     *              the detail lists which (e.g. "PS1: scph5501.bin missing;
     *              PS2: wrong hash for ...") and points at the per-folder README.
     *
     * [BiosValidator.validate] never throws and runs its own IO on Dispatchers.IO;
     * since [runChecks] already runs on [Dispatchers.IO] and the validator confines
     * its own IO, this body needs no extra dispatcher wrap.
     */
    private suspend fun checkBiosFiles(rootPath: String?): HealthCheckResult {
        val id = "bios_files"
        val title = "BIOS files"

        if (rootPath == null) {
            return HealthCheckResult(
                id = id,
                title = title,
                detail = "No storage root configured — skipping BIOS file check.",
                status = HealthStatus.SKIPPED,
            )
        }

        return runCatching {
            val result = biosValidator.validate(rootPath)

            // No root resolved by the validator → nothing to check.
            if (!result.rootConfigured) {
                return@runCatching HealthCheckResult(
                    id = id,
                    title = title,
                    detail = "No storage root configured — skipping BIOS file check.",
                    status = HealthStatus.SKIPPED,
                )
            }

            // Which platform groups did the user actually select an emulator for?
            // Used to decide which bios systems are "intended" vs. just scaffolded.
            val selectedTags = selectedSystemTags()

            // Only systems that actually expect BIOS files (prose-only systems
            // carry an empty expected list and are ignored entirely)…
            val systemsWithExpected = result.systems.filter { it.expectedCount > 0 }
            // …and, of those, only the ones the user shows intent for: a partial
            // dump already present, or an emulator selected for that platform.
            val intendedSystems = systemsWithExpected.filter { sys ->
                sys.presentCount > 0 || (BIOS_SYSTEM_TAGS[sys.system]?.let { it in selectedTags } == true)
            }

            // Nothing the user intended to set up → don't scream about scaffold folders.
            if (intendedSystems.isEmpty() || intendedSystems.none { it.folderExists }) {
                return@runCatching HealthCheckResult(
                    id = id,
                    title = title,
                    detail = "No BIOS files to check yet — add your own legally-dumped " +
                        "BIOS for the systems you use (see each bios/<system>/README.txt).",
                    status = HealthStatus.SKIPPED,
                )
            }

            val totalExpected = intendedSystems.sumOf { it.expectedCount }
            val totalPresent = intendedSystems.sumOf { it.presentCount }

            // Build per-system problem fragments: missing files and wrong hashes.
            val problems = mutableListOf<String>()
            for (sys in intendedSystems) {
                val label = sys.system.uppercase(java.util.Locale.ROOT)
                if (sys.missingFilenames.isNotEmpty()) {
                    problems.add(
                        "$label: ${sys.missingFilenames.joinToString(", ")} missing",
                    )
                }
                if (sys.wrongHashFilenames.isNotEmpty()) {
                    problems.add(
                        "$label: wrong hash for ${sys.wrongHashFilenames.joinToString(", ")}",
                    )
                }
            }

            if (problems.isEmpty()) {
                HealthCheckResult(
                    id = id,
                    title = title,
                    detail = "All $totalExpected expected BIOS files present and hashes OK.",
                    status = HealthStatus.PASS,
                )
            } else {
                HealthCheckResult(
                    id = id,
                    title = title,
                    detail = "$totalPresent of $totalExpected expected BIOS files OK. " +
                        problems.joinToString("; ") + ". " +
                        "Add your own legally-dumped BIOS — see each bios/<system>/README.txt.",
                    status = HealthStatus.WARN,
                )
            }
        }.getOrElse { e ->
            HealthCheckResult(
                id = id,
                title = title,
                detail = "Could not check BIOS files: ${friendlyError(e)}",
                status = HealthStatus.FAIL,
            )
        }
    }

    /**
     * Resolves the user's selected emulator ids to the coarse [SystemTag] groups
     * they belong to, so [checkBiosFiles] can tell which bios systems the user
     * actually intends to populate. Returns an empty set if nothing is selected
     * or the manifest can't be loaded (graceful degrade — the present-file signal
     * still carries intent on its own).
     */
    private suspend fun selectedSystemTags(): Set<SystemTag> {
        val pickedIds = runCatching { selectedApps.pickedIds.first() }.getOrDefault(emptySet())
        if (pickedIds.isEmpty()) return emptySet()

        val isDualScreen = runCatching { setupOptions.isDualScreen.first() }.getOrDefault(false)
        val entries = runCatching {
            if (isDualScreen) manifestParser.loadDualScreen() else manifestParser.loadStandard()
        }.getOrDefault(emptyList())

        return entries.asSequence()
            .filter { it.id in pickedIds }
            .map { it.system }
            .toSet()
    }

    // ── Check 4: Installed emulators ─────────────────────────────────────────

    /**
     * Compares the user's saved app selection ([SelectedAppsStore.pickedIds])
     * against what is currently installed ([InstalledAppsRegistry]).
     *
     * [InstalledAppsRegistry.refresh] is called first to ensure the PackageManager
     * cache is fresh (avoids stale results from a cold-cache blocking call).
     *
     * The manifest is loaded to resolve IDs → display names so the "missing"
     * detail message is human-readable rather than a raw package name string.
     * The correct manifest variant (standard vs. dual-screen) is chosen based
     * on [SetupOptionsStore.isDualScreen] so dual-screen users get accurate names.
     *
     * When selected emulators aren't found by package id, the result is WARN
     * (not FAIL) because this is often a false positive — some forks use a
     * different package id that doesn't match the Obtainium entry id.
     */
    private suspend fun checkInstalledEmulators(): HealthCheckResult =
        withContext(Dispatchers.IO) {
            val id = "installed_emulators"
            val title = "Installed emulators"

            runCatching {
                val pickedIds = selectedApps.pickedIds.first()

                if (pickedIds.isEmpty()) {
                    return@runCatching HealthCheckResult(
                        id = id,
                        title = title,
                        detail = "No emulators were selected during setup.",
                        status = HealthStatus.WARN,
                    )
                }

                // Guarantee a fresh PackageManager query, as instructed by InstalledAppsRegistry docs.
                installedApps.refresh()
                val installedIds = installedApps.snapshot()

                // Build an id → display name map for friendly missing-app labels.
                // Load the correct manifest variant so dual-screen users get real names.
                val isDualScreen = setupOptions.isDualScreen.first()
                val nameMap: Map<String, String> = runCatching {
                    val entries = if (isDualScreen) {
                        manifestParser.loadDualScreen()
                    } else {
                        manifestParser.loadStandard()
                    }
                    entries.associate { entry -> entry.id to entry.name }
                }.getOrDefault(emptyMap())

                val missingIds = pickedIds.filter { it !in installedIds }

                when {
                    missingIds.isEmpty() -> HealthCheckResult(
                        id = id,
                        title = title,
                        detail = "All ${pickedIds.size} selected emulators are installed.",
                        status = HealthStatus.PASS,
                    )
                    missingIds.size == pickedIds.size -> {
                        // All selected emulators not found by package id — likely a false positive
                        // (some forks use a different package id than the Obtainium entry id).
                        val n = missingIds.size
                        HealthCheckResult(
                            id = id,
                            title = title,
                            detail = "Couldn't confirm $n selected emulator${if (n == 1) "" else "s"} " +
                                "by package id — if they're installed this is expected for some forks.",
                            status = HealthStatus.WARN,
                        )
                    }
                    else -> {
                        val installed = pickedIds.size - missingIds.size
                        val n = missingIds.size
                        // Partial miss — still WARN since package-id mismatch is common.
                        HealthCheckResult(
                            id = id,
                            title = title,
                            detail = "$installed of ${pickedIds.size} confirmed installed. " +
                                "Couldn't confirm $n by package id — if they're installed this is " +
                                "expected for some forks.",
                            status = HealthStatus.WARN,
                        )
                    }
                }
            }.getOrElse { e ->
                HealthCheckResult(
                    id = id,
                    title = title,
                    detail = "Could not check installed apps: ${friendlyError(e)}",
                    status = HealthStatus.FAIL,
                )
            }
        }

    // ── Check 5: GPU drivers staged ──────────────────────────────────────────

    /**
     * If the user opted into GPU driver staging ([SetupOptionsStore.stageGpuDrivers]),
     * checks that at least one .zip file is present in Emulation/tools/turnip/.
     *
     * If the user did not opt in, this check is SKIPPED (not FAIL) — the option
     * is a niche feature and most users never enable it.
     *
     * If driver staging is enabled but the GPU is not Adreno, returns WARN —
     * Turnip drivers are Adreno-specific and won't apply to other GPU families.
     *
     * Path resolution reuses [resolveTurnipDir] from [EmulationPaths] to avoid
     * the double-suffix bug (storage root already ends in "Emulation/").
     */
    private suspend fun checkGpuDrivers(rootPath: String?): HealthCheckResult =
        withContext(Dispatchers.IO) {
            val id = "gpu_drivers"
            val title = "GPU drivers"

            val wantsDrivers = setupOptions.stageGpuDrivers.first()

            if (!wantsDrivers) {
                return@withContext HealthCheckResult(
                    id = id,
                    title = title,
                    detail = "GPU driver staging not enabled — check skipped.",
                    status = HealthStatus.SKIPPED,
                )
            }

            // Warn if driver staging is on but the GPU isn't Adreno.
            val gpuInfo = runCatching { gpuDetector.snapshot() }.getOrNull()
            if (gpuInfo != null && gpuInfo.family != GpuFamily.ADRENO) {
                val model = gpuInfo.renderer.ifBlank { gpuInfo.family.name }
                return@withContext HealthCheckResult(
                    id = id,
                    title = title,
                    detail = "GPU driver staging is enabled but your GPU ($model) isn't Adreno — " +
                        "these drivers won't apply.",
                    status = HealthStatus.WARN,
                )
            }

            if (rootPath == null) {
                return@withContext HealthCheckResult(
                    id = id,
                    title = title,
                    detail = "No storage root configured — cannot check driver staging.",
                    status = HealthStatus.FAIL,
                )
            }

            runCatching {
                val turnipDir = resolveTurnipDir(rootPath)
                if (!turnipDir.isDirectory) {
                    return@runCatching HealthCheckResult(
                        id = id,
                        title = title,
                        detail = "tools/turnip/ folder is missing — re-run setup to create it.",
                        status = HealthStatus.FAIL,
                    )
                }
                val zips = turnipDir.listFiles { f -> f.extension.equals("zip", ignoreCase = true) }
                    ?: emptyArray()
                if (zips.isEmpty()) {
                    HealthCheckResult(
                        id = id,
                        title = title,
                        detail = "No driver .zip found in tools/turnip/ — place a driver there.",
                        status = HealthStatus.FAIL,
                    )
                } else {
                    HealthCheckResult(
                        id = id,
                        title = title,
                        detail = "${zips.size} driver package${if (zips.size == 1) "" else "s"} staged in tools/turnip/.",
                        status = HealthStatus.PASS,
                    )
                }
            }.getOrElse { e ->
                HealthCheckResult(
                    id = id,
                    title = title,
                    detail = "Could not check driver staging: ${friendlyError(e)}",
                    status = HealthStatus.FAIL,
                )
            }
        }

    // ── Check 6: Catalog source resolution ──────────────────────────────────

    /**
     * Validates that a bounded set of emulator manifest entries can be resolved
     * to real download URLs, so source rot is caught early instead of via user
     * download failures.
     *
     * Resolution strategy (rate-limit-safe):
     *  - If the device is offline, returns SKIPPED — no network = nothing to check.
     *  - Prefers entries the user has selected (installed path); falls back to a
     *    hard-coded RECOMMENDED set so the check is useful even on fresh installs.
     *  - Skips [trackOnly] entries (they're informational, not installable).
     *  - Caps at [CATALOG_CHECK_CAP] entries to avoid hammering GitHub's API.
     *    At 8 checks, one per source domain, the impact is minimal.
     *  - Runs on IO; any single entry that throws is counted as a failure (not a
     *    crash) so one broken source can't abort the whole check.
     *
     * Result:
     *  - SKIPPED  — offline or no entries to check.
     *  - PASS     — all checked entries resolved OK.
     *  - WARN     — some entries failed; lists the failing names so the user
     *               knows which downloads won't work.
     */
    private suspend fun checkCatalogResolution(): HealthCheckResult =
        withContext(Dispatchers.IO) {
            val id = "catalog_resolution"
            val title = "Catalog sources"

            // Skip entirely when offline — a network failure isn't source rot.
            if (!networkChecker.isOnline()) {
                return@withContext HealthCheckResult(
                    id = id,
                    title = title,
                    detail = "No network — catalog source check skipped.",
                    status = HealthStatus.SKIPPED,
                )
            }

            runCatching {
                val isDualScreen = setupOptions.isDualScreen.first()
                val allEntries: List<AppEntry> = runCatching {
                    if (isDualScreen) manifestParser.loadDualScreen()
                    else manifestParser.loadStandard()
                }.getOrDefault(emptyList())

                if (allEntries.isEmpty()) {
                    return@runCatching HealthCheckResult(
                        id = id,
                        title = title,
                        detail = "Manifest not available — catalog check skipped.",
                        status = HealthStatus.SKIPPED,
                    )
                }

                // Build the candidate set: prefer user-selected entries, then
                // fall back to recommended non-trackOnly entries.
                val pickedIds = runCatching { selectedApps.pickedIds.first() }.getOrDefault(emptySet())
                val candidates: List<AppEntry> = buildList {
                    // Add selected (installed) entries that are in the manifest.
                    if (pickedIds.isNotEmpty()) {
                        addAll(allEntries.filter { it.id in pickedIds && !it.trackOnly })
                    }
                    // Top up with recommended entries if we have room.
                    if (size < CATALOG_CHECK_CAP) {
                        val current = map { it.id }.toSet()
                        addAll(
                            allEntries.filter {
                                it.recommended && !it.trackOnly && it.id !in current
                            }
                        )
                    }
                    // Dedup and cap.
                }.distinctBy { it.id }.take(CATALOG_CHECK_CAP)

                if (candidates.isEmpty()) {
                    return@runCatching HealthCheckResult(
                        id = id,
                        title = title,
                        detail = "No installable entries to check.",
                        status = HealthStatus.SKIPPED,
                    )
                }

                // Resolve each candidate in parallel (bounded to 4 concurrent IO calls)
                // so up to CATALOG_CHECK_CAP=8 sources are probed in ~2 round-trips
                // instead of sequentially (was 2.4–6.4 s, now ~0.8–1.6 s).
                val semaphore = Semaphore(4)
                val resolveResults = coroutineScope {
                    candidates.map { entry ->
                        async(Dispatchers.IO) {
                            semaphore.withPermit {
                                entry to runCatching { sourceRouter.resolve(entry) }
                            }
                        }
                    }.awaitAll()
                }

                val failedNames = mutableListOf<String>()
                var passCount = 0
                for ((entry, result) in resolveResults) {
                    val resolved = result.getOrElse { ResolveResult.Failed(it.message ?: "exception") }
                    if (resolved is ResolveResult.Found) {
                        passCount++
                    } else {
                        failedNames.add(entry.name)
                    }
                }

                val total = candidates.size
                when {
                    failedNames.isEmpty() -> HealthCheckResult(
                        id = id,
                        title = title,
                        detail = "All $total checked sources resolved OK.",
                        status = HealthStatus.PASS,
                    )
                    else -> HealthCheckResult(
                        id = id,
                        title = title,
                        detail = "$passCount of $total resolved OK. " +
                            "These emulators currently can't be downloaded — " +
                            "their source may have moved: ${failedNames.joinToString(", ")}.",
                        status = HealthStatus.WARN,
                    )
                }
            }.getOrElse { e ->
                HealthCheckResult(
                    id = id,
                    title = title,
                    detail = "Could not run catalog check: ${friendlyError(e)}",
                    status = HealthStatus.FAIL,
                )
            }
        }

    // ── Error message helpers ────────────────────────────────────────────────

    /**
     * Maps known exception types to user-friendly messages. SecurityException
     * typically means storage permission was revoked; other exceptions fall
     * back to a readable generic message rather than a raw class name.
     */
    private fun friendlyError(e: Throwable): String = when (e) {
        is SecurityException ->
            "Storage permission was revoked — re-run setup to re-grant it."
        else ->
            e.message?.takeIf { it.isNotBlank() } ?: "Unexpected error (${e.javaClass.simpleName})."
    }

    companion object {
        /**
         * Maximum number of entries to probe in [checkCatalogResolution].
         * Keeps GitHub API usage (and check runtime) bounded regardless of
         * how many emulators the user has selected.
         */
        private const val CATALOG_CHECK_CAP = 8

        /**
         * Maps each bios sub-folder name (as in [FolderSpec.biosFilesBySystem]) to the
         * coarse [SystemTag] whose emulators consume that BIOS. Used by [checkBiosFiles]
         * to decide whether a (still-empty) bios system is "intended" — i.e. the user
         * selected an emulator for that platform — so the BIOS WARN reflects intent
         * rather than the scaffold's always-present folder set.
         *
         * Systems with no clean tag mapping (e.g. the shared "retroarch" system dir)
         * are simply absent; they still count as intended once they have a present
         * file, but are never asserted as "missing" purely from the scaffold.
         */
        private val BIOS_SYSTEM_TAGS: Map<String, SystemTag> = mapOf(
            "ps1" to SystemTag.PLAYSTATION,
            "ps2" to SystemTag.PLAYSTATION,
            "psp" to SystemTag.PLAYSTATION,
            "psvita" to SystemTag.PLAYSTATION,
            "dc" to SystemTag.SEGA,
            "saturn" to SystemTag.SEGA,
            "ds" to SystemTag.NINTENDO_HANDHELD,
            "3ds" to SystemTag.NINTENDO_HANDHELD,
            "gba" to SystemTag.NINTENDO_HANDHELD,
            "switch" to SystemTag.NINTENDO_CONSOLE,
            "wiiu" to SystemTag.NINTENDO_CONSOLE,
        )
    }
}
