package io.github.mayusi.emutran.domain.install

import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Routes install requests to whichever [Installer] is best available
 * right now. Decision happens per-call so toggling Shizuku mid-loop
 * Just Works on the next install.
 *
 * Priority:
 *   1. Shizuku (silent, no dialogs) — if installed, running, and granted.
 *   2. PackageInstaller (system dialog per app) — universal fallback.
 *
 * Loop code never sees the routing logic. It just calls install(file).
 */
@Singleton
class InstallerRouter @Inject constructor(
    private val shizuku: ShizukuInstaller,
    private val shizukuAvailability: ShizukuAvailability,
    private val packageInstaller: PackageInstallerInstaller,
) : Installer {

    override suspend fun install(apk: File): InstallResult {
        return if (shizukuAvailability.snapshot() == ShizukuAvailability.State.INSTALLED_AND_GRANTED) {
            shizuku.install(apk)
        } else {
            packageInstaller.install(apk)
        }
    }

    /** Exposed for UI to render the current mode. */
    fun currentMode(): Mode =
        if (shizukuAvailability.snapshot() == ShizukuAvailability.State.INSTALLED_AND_GRANTED) {
            Mode.SHIZUKU_SILENT
        } else {
            Mode.SYSTEM_DIALOG
        }

    enum class Mode { SHIZUKU_SILENT, SYSTEM_DIALOG }
}
