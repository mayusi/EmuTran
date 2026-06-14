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
    data class Failed(val message: String) : InstallResult
}
