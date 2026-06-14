package io.github.mayusi.emutran.domain.install

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Standard install path: hand the APK to Android's package installer via
 * ACTION_VIEW + the package-archive MIME type. The user will see the
 * stock "Install / Cancel" dialog once per APK.
 *
 * This is the fallback for users without Shizuku. Week 4 will add the
 * Shizuku silent-install path that bypasses this dialog entirely.
 *
 * Two non-obvious requirements satisfied here:
 *  1. The URI must come from FileProvider, not a file:// path —
 *     Android cracks down on cross-app file:// URIs since N.
 *  2. FLAG_GRANT_READ_URI_PERMISSION must accompany the intent so the
 *     package installer can actually read the APK we hand it.
 */
@Singleton
class IntentInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /**
     * Whether the OS currently allows this app to install other APKs.
     * Android 8+ requires per-app "Install unknown apps" approval. If
     * this returns false, call [openManageUnknownAppsSettings] to send
     * the user to the right settings page.
     */
    fun canRequestInstalls(): Boolean =
        context.packageManager.canRequestPackageInstalls()

    /** Deep-link to "Install unknown apps" page for this specific app. */
    fun openManageUnknownAppsSettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun install(apk: File) {
        val authority = "${context.packageName}.fileprovider"
        val uri = FileProvider.getUriForFile(context, authority, apk)

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        context.startActivity(intent)
    }
}
