package io.github.mayusi.emutran.ui.testinstall

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.mayusi.emutran.domain.download.ApkDownloader
import io.github.mayusi.emutran.domain.install.IntentInstaller
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * One-button proof of the download → install pipeline.
 *
 * Hardcoded to Flycast (Dreamcast/NAOMI/Atomiswave emulator) because:
 *  - GitHub Releases hosts a real Android APK directly (HTTPS, no
 *    redirects to HTTP, no cleartext-policy issues)
 *  - ~31 MB → fast test download
 *  - The APK is a universal build (works on the Odin's arm64)
 *  - Listed in the Obtainium pack so this exercises a real entry
 *
 * Earlier attempts:
 *  - PPSSPP site → redirects HTTPS to HTTP, blocked by network policy
 *  - DuckStation → doesn't ship Android APKs on GitHub anymore (they
 *    use a separate mirror), only PC builds. The Obtainium pack
 *    handles DuckStation via that mirror, not GitHub.
 */
@HiltViewModel
class TestInstallViewModel @Inject constructor(
    private val downloader: ApkDownloader,
    private val installer: IntentInstaller,
) : ViewModel() {

    // Pinned to a specific Flycast release so the URL stays valid even
    // if their asset naming changes in a future release. Bump manually
    // until the Week 2 manifest-driven engine takes over.
    private val testApkUrl =
        "https://github.com/flyinghead/flycast/releases/download/v2.6/flycast-2.6.apk"
    private val testApkFilename = "flycast-test.apk"

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** True iff Android will let us launch the install intent right now. */
    fun canInstall(): Boolean = installer.canRequestInstalls()

    /** Open the per-app "Install unknown apps" settings page. */
    fun openInstallSettings() = installer.openManageUnknownAppsSettings()

    fun runTest() {
        if (_state.value is State.Downloading) return
        _state.update { State.Downloading(0, 0) }

        viewModelScope.launch {
            downloader.download(testApkUrl, testApkFilename).collect { progress ->
                when (progress) {
                    is ApkDownloader.Progress.Started ->
                        _state.update { State.Downloading(0, progress.totalBytes) }
                    is ApkDownloader.Progress.Chunk ->
                        _state.update {
                            State.Downloading(progress.downloaded, progress.totalBytes)
                        }
                    is ApkDownloader.Progress.Done -> {
                        // Fire the system installer. On success the user
                        // returns to this screen with the app installed.
                        try {
                            installer.install(progress.file)
                            _state.update { State.LaunchedInstaller(progress.file.length()) }
                        } catch (t: Throwable) {
                            _state.update { State.Failed("Install intent failed: ${t.message}") }
                        }
                    }
                    is ApkDownloader.Progress.Failed ->
                        _state.update { State.Failed(progress.message) }
                }
            }
        }
    }

    sealed interface State {
        data object Idle : State
        data class Downloading(val downloaded: Long, val total: Long) : State
        data class LaunchedInstaller(val sizeBytes: Long) : State
        data class Failed(val message: String) : State
    }
}
