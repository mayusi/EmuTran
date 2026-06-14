package io.github.mayusi.emutran.ui.permissions

import android.content.Intent
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.mayusi.emutran.data.storage.AllFilesAccess
import io.github.mayusi.emutran.domain.install.IntentInstaller
import javax.inject.Inject

/**
 * Backs the up-front permissions wizard step. Surfaces the two special-access
 * permissions (All files / Install unknown apps) so the screen can show their
 * live grant state and deep-link the user into Settings. POST_NOTIFICATIONS is
 * a runtime permission handled directly in the composable via a launcher.
 */
@HiltViewModel
class PermissionsViewModel @Inject constructor(
    private val allFiles: AllFilesAccess,
    private val installer: IntentInstaller,
) : ViewModel() {

    // --- All files access (MANAGE_EXTERNAL_STORAGE) ---

    fun hasAllFilesAccess(): Boolean = allFiles.isGranted()

    fun allFilesAccessIntent(): Intent = allFiles.requestIntent()

    // --- Install unknown apps (REQUEST_INSTALL_PACKAGES) ---

    fun canRequestInstalls(): Boolean = installer.canRequestInstalls()

    fun openInstallUnknownAppsSettings() = installer.openManageUnknownAppsSettings()
}
