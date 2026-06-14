package io.github.mayusi.emutran.domain.install

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Silent install via Shizuku's privileged shell access.
 *
 * Works by piping the APK bytes into `pm install -i <our-pkg> -S <size>`
 * running as the shell user (UID 2000). That's the same trick adb install
 * uses internally — Shizuku exposes the shell user to apps that have
 * its permission, no root needed.
 *
 * Cancellation isn't user-visible here (there's no dialog to cancel),
 * so we never emit Cancelled — only Installed / Failed.
 *
 * Pre-conditions checked by [InstallerRouter] before we get here:
 *   - Shizuku is installed and running
 *   - User has granted EmuTran Shizuku permission
 * If those aren't true, the pm subprocess will exit with permission
 * errors and we return Failed.
 */
@Singleton
class ShizukuInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
) : Installer {

    override suspend fun install(apk: File): InstallResult = withContext(Dispatchers.IO) {
        try {
            // -i installerPackageName so the OS records EmuTran as the source.
            // -r allows reinstall over an existing install (useful when the
            // user re-runs setup to update).
            val cmd = arrayOf(
                "sh", "-c",
                "pm install -i ${context.packageName} -r -S ${apk.length()}"
            )

            // Shizuku marked newProcess() private starting in api 13; the
            // method itself still exists, so the community-standard fix
            // is reflection. Same approach Obtainium, IzzyOnDroid, and a
            // dozen other "install other apps" tools use.
            val process = invokeNewProcessReflective(cmd)
                ?: return@withContext InstallResult.Failed(
                    "Shizuku.newProcess unavailable — Shizuku version mismatch?"
                )

            // Pipe APK bytes into stdin of pm install.
            val stdin = process.javaClass.getMethod("getOutputStream")
                .invoke(process) as java.io.OutputStream
            stdin.use { out ->
                apk.inputStream().use { src -> src.copyTo(out) }
            }

            val exit = process.javaClass.getMethod("waitFor").invoke(process) as Int
            val stdout = (process.javaClass.getMethod("getInputStream").invoke(process)
                as java.io.InputStream).bufferedReader().use { it.readText() }
            val stderr = (process.javaClass.getMethod("getErrorStream").invoke(process)
                as java.io.InputStream).bufferedReader().use { it.readText() }

            if (exit == 0 && "Success" in stdout) {
                InstallResult.Installed
            } else {
                val msg = stderr.takeIf { it.isNotBlank() }
                    ?: stdout.takeIf { it.isNotBlank() }
                    ?: "pm install exited $exit"
                InstallResult.Failed(msg.trim().take(300))
            }
        } catch (t: Throwable) {
            InstallResult.Failed(t.message ?: t.javaClass.simpleName)
        }
    }

    /**
     * Reflectively call Shizuku.newProcess(String[], String[], String).
     * Method is private but still present; AccessibleObject lets us call it.
     */
    private fun invokeNewProcessReflective(cmd: Array<String>): Any? {
        val shizukuClass = Class.forName("rikka.shizuku.Shizuku")
        val method = shizukuClass.declaredMethods.firstOrNull {
            it.name == "newProcess" && it.parameterTypes.size == 3
        } ?: return null
        method.isAccessible = true
        return method.invoke(null, cmd, null, null)
    }
}
