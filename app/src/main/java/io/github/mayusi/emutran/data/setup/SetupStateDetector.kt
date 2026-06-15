package io.github.mayusi.emutran.data.setup

import io.github.mayusi.emutran.data.device.InstalledAppsRegistry
import io.github.mayusi.emutran.data.manifest.ObtainiumPackParser
import io.github.mayusi.emutran.data.selection.SelectedAppsStore
import io.github.mayusi.emutran.data.storage.StorageRootStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decides whether to send the user straight to the dashboard on launch,
 * or run them through the initial setup flow.
 *
 * Inferred from existing state rather than a setup_complete flag, per
 * user request. The trade-off is fragility: if the user nukes the
 * Emulation/ folder, we'll loop them back through setup. The win is
 * we never lie about being "set up" when nothing actually is.
 *
 * Three signals, ANY combination of which we trust as "yes, set up":
 *   1. A previously persisted root path exists on disk AND has an
 *      Emulation/ tree inside it (the most direct evidence).
 *   2. The user has saved a selection of apps in SelectedAppsStore.
 *   3. At least one of the manifest's apps is currently installed on
 *      the device (matches our recommended IDs).
 *
 * Requiring TWO of these signals reduces false positives — a brand-new
 * device that happens to have RetroArch from another source shouldn't
 * count as "EmuTran is set up here."
 */
@Singleton
class SetupStateDetector @Inject constructor(
    private val storageRoot: StorageRootStore,
    private val selectedApps: SelectedAppsStore,
    private val installedApps: InstalledAppsRegistry,
    private val manifestParser: ObtainiumPackParser,
) {
    suspend fun isSetUp(): Boolean = withContext(Dispatchers.IO) {
        var signals = 0

        // Signal 1: persisted root + Emulation/ tree present.
        val rootPath = storageRoot.rootPath.first()
        if (rootPath != null && hasEmulationTree(rootPath)) signals++

        // Signal 2: a non-empty saved app selection.
        val picks = selectedApps.pickedIds.first()
        if (picks.isNotEmpty()) signals++

        // Signal 3: at least one manifest app currently installed.
        // Uses loadBundledOnly() (no network, no disk cache) so startup routing
        // works fully offline on first cold boot without waiting on GitHub Raw.
        // The bundled asset is always accurate enough for this routing check;
        // the live network refresh happens lazily when the user opens Dashboard
        // or PickApps (via loadStandard/loadDualScreen, unchanged).
        val installed = installedApps.snapshot()
        val manifestIds = runCatching { manifestParser.loadBundledOnly() }
            .getOrDefault(emptyList())
            .map { it.id }
            .toSet()
        if (installed.any { it in manifestIds }) signals++

        signals >= 2
    }

    internal fun hasEmulationTree(rootPath: String): Boolean {
        val root = File(rootPath)
        val emulationDir = if (root.name.equals("Emulation", ignoreCase = true)) {
            root
        } else {
            File(root, "Emulation")
        }
        // We consider the tree "present" if any of the canonical
        // top-level subdirs exist — not just the root, since a user
        // could have an empty 'Emulation' folder from somewhere else.
        if (!emulationDir.isDirectory) return false
        val sentinels = listOf("roms", "bios", "saves", "tools")
        return sentinels.any { File(emulationDir, it).isDirectory }
    }
}
