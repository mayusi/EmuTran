package io.github.mayusi.emutran.domain.install

import android.content.Context
import android.content.Intent
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fires Android's system uninstall dialog for a given package.
 *
 * Like install, we can't silently uninstall without Shizuku (and even
 * with Shizuku it's a separate pm uninstall command, not worth the
 * complexity for v0.x). The system dialog is fine — user confirms once
 * per app.
 *
 * Caller is responsible for refreshing installed state after the user
 * comes back, since we don't get a callback.
 */
@Singleton
class Uninstaller @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun requestUninstall(packageName: String) {
        val intent = Intent(Intent.ACTION_UNINSTALL_PACKAGE).apply {
            data = Uri.parse("package:$packageName")
            putExtra(Intent.EXTRA_RETURN_RESULT, false)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (t: Throwable) {
            // Some firmware-restricted entries can't be uninstalled — silent.
        }
    }
}
