package io.github.mayusi.emutran.ui.shizuku

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.mayusi.emutran.data.manifest.AppEntry
import io.github.mayusi.emutran.data.manifest.SourceKind
import io.github.mayusi.emutran.data.manifest.SystemTag
import io.github.mayusi.emutran.data.source.GitHubReleasesSource
import io.github.mayusi.emutran.data.source.ResolveResult
import io.github.mayusi.emutran.data.storage.SetupOptionsStore
import io.github.mayusi.emutran.domain.download.ApkDownloader
import io.github.mayusi.emutran.domain.install.InstallResult
import io.github.mayusi.emutran.domain.install.PackageInstallerInstaller
import io.github.mayusi.emutran.domain.install.ShizukuAvailability
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ShizukuViewModel @Inject constructor(
    private val availability: ShizukuAvailability,
    private val downloader: ApkDownloader,
    private val githubSource: GitHubReleasesSource,
    // Always use the system installer for Shizuku itself — there's no
    // Shizuku running yet by definition, so the silent path would never
    // work for this one app.
    private val installer: PackageInstallerInstaller,
    private val setupOptions: SetupOptionsStore,
) : ViewModel() {

    /** Whether the user opted in to staging custom GPU drivers. */
    val stageGpuDrivers: StateFlow<Boolean> = setupOptions.stageGpuDrivers
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setStageGpuDrivers(value: Boolean) {
        viewModelScope.launch { setupOptions.setStageGpuDrivers(value) }
    }

    private val _state = MutableStateFlow(availability.snapshot())
    val state: StateFlow<ShizukuAvailability.State> = _state.asStateFlow()

    private val _installFlow = MutableStateFlow<InstallFlow>(InstallFlow.Idle)
    val installFlow: StateFlow<InstallFlow> = _installFlow.asStateFlow()

    /** Re-check current Shizuku state. Call on ON_RESUME. */
    fun refresh() {
        _state.value = availability.snapshot()
    }

    fun requestPermission() = availability.requestPermission(REQUEST_CODE)

    /**
     * Download Shizuku's APK and install it through our existing pipeline.
     * User still has to tap Install in Android's system dialog (we don't
     * have Shizuku yet — that's the whole point) but at least they
     * don't have to leave the app + visit GitHub + sideload manually.
     */
    fun autoInstallShizuku() {
        if (_installFlow.value is InstallFlow.Downloading ||
            _installFlow.value is InstallFlow.Installing
        ) return

        viewModelScope.launch {
            // Resolve the current Shizuku release dynamically so we don't
            // ship with a stale hardcoded URL that 404s.
            val resolved = githubSource.resolve(shizukuFakeEntry())
            val url: String
            val filename: String
            when (resolved) {
                is ResolveResult.Found -> {
                    url = resolved.apkUrl
                    filename = resolved.filename
                }
                is ResolveResult.Failed -> {
                    _installFlow.update { InstallFlow.Failed("Could not find Shizuku release: ${resolved.reason}") }
                    return@launch
                }
                ResolveResult.Unsupported -> {
                    _installFlow.update { InstallFlow.Failed("GitHub source unavailable for Shizuku") }
                    return@launch
                }
            }

            _installFlow.update { InstallFlow.Downloading(0, 0) }
            var apkFile: java.io.File? = null
            downloader.download(url, filename).collect { p ->
                when (p) {
                    is ApkDownloader.Progress.Started ->
                        _installFlow.update { InstallFlow.Downloading(0, p.totalBytes) }
                    is ApkDownloader.Progress.Chunk ->
                        _installFlow.update { InstallFlow.Downloading(p.downloaded, p.totalBytes) }
                    is ApkDownloader.Progress.Done -> apkFile = p.file
                    is ApkDownloader.Progress.Failed ->
                        _installFlow.update { InstallFlow.Failed(p.message) }
                }
            }
            val f = apkFile ?: return@launch
            _installFlow.update { InstallFlow.Installing }
            val r = installer.install(f)
            _installFlow.update {
                when (r) {
                    InstallResult.Installed -> {
                        refresh() // pick up new availability state
                        InstallFlow.InstalledOk
                    }
                    InstallResult.Cancelled -> InstallFlow.Cancelled
                    // "Install unknown apps" is off for EmuTran, so the OS won't let
                    // us install Shizuku. Report it as a failure with an actionable
                    // reason — this VM has no settings deep-link, but the message tells
                    // the user exactly which permission to enable before retrying.
                    InstallResult.NeedsPermission ->
                        InstallFlow.Failed(
                            "Enable 'Install unknown apps' for EmuTran in system settings, then retry"
                        )
                    is InstallResult.Failed -> InstallFlow.Failed(r.message)
                }
            }
        }
    }

    /**
     * Synthesize an [AppEntry] for Shizuku so GitHubReleasesSource can
     * resolve its latest release the same way it does for any other app.
     * Shizuku isn't part of the Obtainium pack so we hand-build it.
     */
    private fun shizukuFakeEntry() = AppEntry(
        id = "moe.shizuku.privileged.api",
        name = "Shizuku",
        author = "RikkaApps",
        about = "Shell-level access for Android apps",
        sourceUrl = "https://github.com/RikkaApps/Shizuku",
        source = SourceKind.GITHUB,
        apkFilterRegEx = "",
        invertApkFilter = false,
        autoFilterByArch = true,
        includePrereleases = false,
        fallbackToOlderReleases = false,
        versionExtractionRegEx = "",
        filterReleaseTitlesRegEx = "",
        categories = emptyList(),
        trackOnly = false,
        system = SystemTag.UTILITY,
        recommended = false,
    )

    sealed interface InstallFlow {
        data object Idle : InstallFlow
        data class Downloading(val downloaded: Long, val total: Long) : InstallFlow
        data object Installing : InstallFlow
        data object InstalledOk : InstallFlow
        data object Cancelled : InstallFlow
        data class Failed(val message: String) : InstallFlow
    }

    companion object {
        const val REQUEST_CODE = 0x517A
    }
}
