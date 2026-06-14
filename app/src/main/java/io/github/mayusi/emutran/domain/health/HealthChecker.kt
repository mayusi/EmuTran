package io.github.mayusi.emutran.domain.health

import io.github.mayusi.emutran.data.device.InstalledAppsRegistry
import io.github.mayusi.emutran.data.manifest.ObtainiumPackParser
import io.github.mayusi.emutran.data.selection.SelectedAppsStore
import io.github.mayusi.emutran.data.storage.SetupOptionsStore
import io.github.mayusi.emutran.data.storage.StorageRootStore
import io.github.mayusi.emutran.domain.scaffold.FolderSpec
import io.github.mayusi.emutran.domain.scaffold.resolveEmulationRoot
import io.github.mayusi.emutran.domain.scaffold.resolveTurnipDir
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
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
 * list of [HealthCheckResult].  All four checks are independent; a failure in
 * one does not prevent the rest from running.
 *
 * All IO is performed on [Dispatchers.IO] — safe to call from any coroutine.
 *
 * Checks (in display order):
 *  1. Emulation folder tree exists and is readable.
 *  2. BIOS sub-folders are present under Emulation/bios/.
 *  3. Selected emulators are still installed on the device.
 *  4. GPU driver .zip staged in tools/turnip/ (if user opted in).
 */
@Singleton
class HealthChecker @Inject constructor(
    private val storageRoot: StorageRootStore,
    private val selectedApps: SelectedAppsStore,
    private val installedApps: InstalledAppsRegistry,
    private val setupOptions: SetupOptionsStore,
    private val manifestParser: ObtainiumPackParser,
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
            checkInstalledEmulators(),
            checkGpuDrivers(rootPath),
        )
    }

    // ── Check 1: Emulation folder tree ───────────────────────────────────────

    /**
     * Verifies that the saved storage root exists on disk and contains a
     * recognisable Emulation/ sub-tree.
     *
     * Logic mirrors [SetupStateDetector.hasEmulationTree] (which is private
     * and cannot be called directly): the root + Emulation/ dir must exist,
     * and at least one of the canonical sentinel sub-dirs (roms / bios /
     * saves / tools) must be present — an empty Emulation/ folder does not
     * count.
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
            val sentinels = listOf("roms", "bios", "saves", "tools")
            val found = sentinels.filter { File(emulationDir, it).isDirectory }
            if (found.isEmpty()) {
                HealthCheckResult(
                    id = id,
                    title = title,
                    detail = "Emulation/ exists but appears empty (no roms/bios/saves/tools).",
                    status = HealthStatus.WARN,
                )
            } else {
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
                detail = "Could not read storage root: ${e.message}",
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
                detail = "Could not check BIOS folders: ${e.message}",
                status = HealthStatus.FAIL,
            )
        }
    }

    // ── Check 3: Installed emulators ─────────────────────────────────────────

    /**
     * Compares the user's saved app selection ([SelectedAppsStore.pickedIds])
     * against what is currently installed ([InstalledAppsRegistry]).
     *
     * [InstalledAppsRegistry.refresh] is called first to ensure the PackageManager
     * cache is fresh (avoids stale results from a cold-cache blocking call).
     *
     * The manifest is loaded to resolve IDs → display names so the "missing"
     * detail message is human-readable rather than a raw package name string.
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
                val nameMap: Map<String, String> = runCatching {
                    manifestParser.loadStandard()
                        .associate { entry -> entry.id to entry.name }
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
                        val names = missingIds
                            .joinToString(", ") { nameMap[it] ?: it }
                        HealthCheckResult(
                            id = id,
                            title = title,
                            detail = "All selected emulators appear uninstalled: $names",
                            status = HealthStatus.FAIL,
                        )
                    }
                    else -> {
                        val installed = pickedIds.size - missingIds.size
                        val names = missingIds
                            .joinToString(", ") { nameMap[it] ?: it }
                        HealthCheckResult(
                            id = id,
                            title = title,
                            detail = "$installed of ${pickedIds.size} installed. Missing: $names",
                            status = HealthStatus.FAIL,
                        )
                    }
                }
            }.getOrElse { e ->
                HealthCheckResult(
                    id = id,
                    title = title,
                    detail = "Could not check installed apps: ${e.message}",
                    status = HealthStatus.FAIL,
                )
            }
        }

    // ── Check 4: GPU drivers staged ──────────────────────────────────────────

    /**
     * If the user opted into GPU driver staging ([SetupOptionsStore.stageGpuDrivers]),
     * checks that at least one .zip file is present in Emulation/tools/turnip/.
     *
     * If the user did not opt in, this check is SKIPPED (not FAIL) — the option
     * is a niche feature and most users never enable it.
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
                    detail = "Could not check driver staging: ${e.message}",
                    status = HealthStatus.FAIL,
                )
            }
        }
}
