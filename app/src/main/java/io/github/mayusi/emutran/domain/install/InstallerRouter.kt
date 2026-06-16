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

    /**
     * Whether the OS currently allows this app to install other APKs.
     * Delegates to [PackageInstallerInstaller] — the dialog path is the one
     * gated by the per-app "Install unknown apps" grant (Shizuku silent
     * install bypasses it). Exposed so the emulator-install UI can gate the
     * same way the self-update path does via [IntentInstaller].
     */
    fun canRequestInstalls(): Boolean =
        packageInstaller.canRequestInstalls()

    /** Deep-link the user to the per-app "Install unknown apps" settings page. */
    fun openInstallPermissionSettings() {
        packageInstaller.openManageUnknownAppsSettings()
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
