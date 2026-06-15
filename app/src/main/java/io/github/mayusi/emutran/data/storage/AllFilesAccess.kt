package io.github.mayusi.emutran.data.storage

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Helper around storage permissions. The behaviour differs by API level:
 *
 *   API 30+ (Android 11+): MANAGE_EXTERNAL_STORAGE ("All files access") is a
 *   special-access permission with its own Settings page. We check via
 *   Environment.isExternalStorageManager() and deep-link to
 *   ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION.
 *
 *   API 29 (Android 10 / minSdk): MANAGE_EXTERNAL_STORAGE does not exist.
 *   The model is WRITE_EXTERNAL_STORAGE (declared in the manifest with
 *   maxSdkVersion="29") which is granted at install time when the user
 *   approves the permission group. We check via ContextCompat.checkSelfPermission
 *   and, if somehow not granted, fall back to the app-details Settings page
 *   (ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION doesn't exist on API 29
 *   and would throw ActivityNotFoundException).
 *
 * IMPORTANT: Environment.isExternalStorageManager() is only available on
 * API 30+. Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION is only
 * available on API 30+. Both calls MUST be gated behind a Build.VERSION
 * check to avoid NoSuchMethodError / ActivityNotFoundException on API 29.
 */
@Singleton
class AllFilesAccess @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /**
     * Returns true when the app has the storage access it needs.
     *
     * API 30+: checks MANAGE_EXTERNAL_STORAGE (Environment.isExternalStorageManager).
     * API 29:  checks WRITE_EXTERNAL_STORAGE (install-time; almost always granted
     *          if the manifest declares it with maxSdkVersion="29").
     */
    fun isGranted(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // API 30+: MANAGE_EXTERNAL_STORAGE special-access permission.
            Environment.isExternalStorageManager()
        } else {
            // API 29 (Android 10): classic WRITE_EXTERNAL_STORAGE.
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED
        }

    /**
     * Intent that opens the appropriate storage-permission Settings page.
     *
     * API 30+: deep-links to the "Allow access to manage all files" page for
     *   this app (ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).
     * API 29: falls back to the generic app-details Settings page
     *   (ACTION_APPLICATION_DETAILS_SETTINGS) because the all-files-access page
     *   does not exist on Android 10 and launching it would throw
     *   ActivityNotFoundException.
     *
     * Caller must `startActivity` from an Activity context (we add NEW_TASK
     * for safety when launched from a non-activity scope).
     */
    fun requestIntent(): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // API 30+: dedicated all-files-access Settings page.
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                .setData(Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else {
            // API 29: app-details Settings page (WRITE_EXTERNAL_STORAGE is
            // normally install-time granted; this lets the user check manually).
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
}
