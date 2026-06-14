package io.github.mayusi.emutran.data.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Helper around the MANAGE_EXTERNAL_STORAGE permission (a.k.a. "All files
 * access"). Required because the Odin's SAF picker is too restrictive to
 * complete our scaffolding flow.
 *
 * On Android 11+ this is a separate Settings page, not a runtime prompt.
 * We deep-link the user there from the permissions screen.
 */
@Singleton
class AllFilesAccess @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun isGranted(): Boolean =
        Environment.isExternalStorageManager()

    /**
     * Intent that opens the "Allow access to manage all files" Settings page
     * focused on this app. Caller must `startActivity` from an Activity ctx
     * (we use NEW_TASK for safety when launched from a non-activity scope).
     */
    fun requestIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
