package io.github.mayusi.emutran.domain.install

import android.content.Context
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Three-state view of Shizuku:
 *
 *  - INSTALLED_AND_GRANTED: Shizuku app present, service alive, our app
 *    has been granted Shizuku permission. We can use the silent path.
 *  - INSTALLED_NEEDS_PERMISSION: Shizuku is running but the user hasn't
 *    granted EmuTran access yet. We can ask for it.
 *  - NOT_RUNNING: Either not installed, or installed but the service
 *    isn't started. User has to set it up manually (one-time).
 *
 * We isolate every Shizuku.* call behind try/catch because the
 * Shizuku library NPEs in a few edge cases (called before the binder
 * is ready, called when Shizuku isn't installed at all, etc.). Treating
 * any error as NOT_RUNNING is safe: we just fall back to the system
 * installer dialogs.
 */
@Singleton
class ShizukuAvailability @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun snapshot(): State {
        // Quick reject: the Shizuku app isn't even installed.
        if (!isShizukuAppPresent()) return State.NOT_RUNNING

        return try {
            // Wrap in try because Shizuku throws if the binder isn't ready.
            val available = runCatching {
                rikka.shizuku.Shizuku.pingBinder()
            }.getOrElse { false }

            if (!available) return State.NOT_RUNNING

            val granted = runCatching {
                rikka.shizuku.Shizuku.checkSelfPermission() ==
                    PackageManager.PERMISSION_GRANTED
            }.getOrElse { false }

            if (granted) State.INSTALLED_AND_GRANTED
            else State.INSTALLED_NEEDS_PERMISSION
        } catch (t: Throwable) {
            State.NOT_RUNNING
        }
    }

    fun isShizukuAppPresent(): Boolean = try {
        context.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    } catch (t: Throwable) {
        false
    }

    /** Try to request runtime permission. Caller passes a unique requestCode. */
    fun requestPermission(requestCode: Int): Boolean = try {
        rikka.shizuku.Shizuku.requestPermission(requestCode)
        true
    } catch (t: Throwable) {
        false
    }

    enum class State { INSTALLED_AND_GRANTED, INSTALLED_NEEDS_PERMISSION, NOT_RUNNING }

    companion object {
        const val PLAY_STORE_URL =
            "https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api"
        const val GITHUB_URL = "https://github.com/RikkaApps/Shizuku/releases/latest"
    }
}
