package io.github.mayusi.emutran.domain.install

import java.io.File

/**
 * Common contract for "install this APK and tell me what happened."
 *
 * Two implementations:
 *  - [PackageInstallerInstaller] — system dialog, one tap per app.
 *  - [ShizukuInstaller]          — silent, requires Shizuku running.
 *
 * The progress loop calls into [InstallerRouter] which picks the best
 * available implementation. Loop code stays identical either way.
 */
interface Installer {
    suspend fun install(apk: File): InstallResult
}

sealed interface InstallResult {
    data object Installed : InstallResult
    data object Cancelled : InstallResult

    /**
     * The OS won't let us install yet — the per-app "Install unknown apps"
     * grant (Android 8+) is missing. The caller should deep-link the user to
     * settings (see [InstallerRouter.openInstallPermissionSettings]) and retry.
     */
    data object NeedsPermission : InstallResult
    data class Failed(val message: String) : InstallResult
}
