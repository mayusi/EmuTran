package io.github.mayusi.emutran.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.mayusi.emutran.data.device.InstalledAppsRegistry
import io.github.mayusi.emutran.data.manifest.AppEntry
import io.github.mayusi.emutran.data.manifest.ObtainiumPackParser
import io.github.mayusi.emutran.data.manifest.SourceKind
import io.github.mayusi.emutran.data.manifest.SystemTag
import io.github.mayusi.emutran.data.selection.SelectedAppsStore
import io.github.mayusi.emutran.data.source.AppSourceRouter
import io.github.mayusi.emutran.data.source.AssetKind
import io.github.mayusi.emutran.data.source.ResolveResult
import io.github.mayusi.emutran.data.storage.SetupOptionsStore
import io.github.mayusi.emutran.data.storage.StorageRootStore
import io.github.mayusi.emutran.data.update.SelfUpdateRepository
import io.github.mayusi.emutran.data.update.SelfUpdateResult
import io.github.mayusi.emutran.data.update.UpdateInfo
import io.github.mayusi.emutran.data.update.UpdateProgress
import io.github.mayusi.emutran.data.update.UpdateRepository
import io.github.mayusi.emutran.domain.download.ApkDownloader
import io.github.mayusi.emutran.domain.install.InstallerRouter
import io.github.mayusi.emutran.domain.install.Uninstaller
import io.github.mayusi.emutran.domain.scaffold.resolveTurnipDir
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * Dashboard: shows every manifest emulator with its installed/not
 * status, supports per-app reinstall (== update) and uninstall.
 *
 * Update detection: lightweight per-app update state is provided by
 * [UpdateRepository.updateState] and surfaced as [updateState] / [updateCount].
 * Real-time progress from [UpdateRepository.updateProgressFlow] is reflected
 * per-card as [updateProgressMap]. "Check now" calls [updateRepository.checkNow]
 * with force=true; "Update all" calls [updateRepository.updateAll].
 *
 * Self-update banner: on init, [SelfUpdateRepository.bannerState] is called
 * (24h-gated, respects the user's "skip this version" preference). The result
 * is held in [selfUpdate]. When Available, the dashboard renders an inline
 * banner above the EmuHelper card. Tapping "What's new" opens a ModalBottomSheet
 * with [SelfUpdateSheetUiState] driving the download progress.
 *
 * Uninstall fires the system uninstall intent — Android handles the
 * confirmation dialog, we react to ON_RESUME by refreshing the installed
 * snapshot.
 */
@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val parser: ObtainiumPackParser,
    private val installed: InstalledAppsRegistry,
    private val selectedStore: SelectedAppsStore,
    private val router: AppSourceRouter,
    private val downloader: ApkDownloader,
    private val installer: InstallerRouter,
    private val storage: StorageRootStore,
    private val uninstaller: Uninstaller,
    private val setupOptions: SetupOptionsStore,
    private val updateRepository: UpdateRepository,
    private val selfUpdateRepository: SelfUpdateRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<UiState>(UiState.Loading)
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Per-app action in-flight tracking so the UI can disable buttons. */
    private val _busy = MutableStateFlow<Set<String>>(emptySet())
    val busy: StateFlow<Set<String>> = _busy.asStateFlow()

    /** True while the featured EmuHelper APK is downloading/installing. */
    private val _emuHelperInstalling = MutableStateFlow(false)
    val emuHelperInstalling: StateFlow<Boolean> = _emuHelperInstalling.asStateFlow()

    /** User-facing messages for update/install/uninstall actions (success or failure). */
    private val _userMessage = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val userMessage: SharedFlow<String> = _userMessage.asSharedFlow()

    // ── Self-update banner ───────────────────────────────────────────────────

    /**
     * 24h-gated EmuTran self-update check. Null until the initial check
     * completes. Set to [SelfUpdateResult.Available] when a new release is
     * found (and the user hasn't skipped it). Cleared by [dismissSelfUpdateBanner]
     * (session-only) or [skipSelfUpdate] (persisted).
     */
    private val _selfUpdate = MutableStateFlow<SelfUpdateResult?>(null)
    val selfUpdate: StateFlow<SelfUpdateResult?> = _selfUpdate.asStateFlow()

    /**
     * Bottom-sheet state for the self-update flow on the dashboard.
     * Null → sheet closed. Non-null → sheet open with given state.
     */
    private val _selfUpdateSheet = MutableStateFlow<SelfUpdateSheetUiState?>(null)
    val selfUpdateSheet: StateFlow<SelfUpdateSheetUiState?> = _selfUpdateSheet.asStateFlow()

    /** Active download job so [dismissSelfUpdateSheet] can cancel it. */
    private var selfUpdateDownloadJob: Job? = null

    // ── Update-repository wiring ─────────────────────────────────────────────

    /**
     * Map of entryId → UpdateInfo. Drives per-card "Update vX" chip display.
     * Collected from [UpdateRepository.updateState] — always fresh from DataStore.
     */
    val updateState: StateFlow<Map<String, UpdateInfo>> = updateRepository.updateState()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /**
     * Number of installed apps with a detected update. Drives the header
     * "Update all" hero when > 0.
     */
    val updateCount: StateFlow<Int> = updateRepository.updateCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /**
     * Real-time per-entryId progress snapshot.
     * Key = entryId; value = latest [UpdateProgress] event for that app.
     * Cleared when [UpdateProgress.Done] / [UpdateProgress.Cancelled] /
     * [UpdateProgress.Failed] arrives so stale spinners don't linger.
     */
    private val _updateProgressMap = MutableStateFlow<Map<String, UpdateProgress>>(emptyMap())
    val updateProgressMap: StateFlow<Map<String, UpdateProgress>> = _updateProgressMap.asStateFlow()

    /** The featured EmuHelper card's app entry. */
    val emuHelper: AppEntry = emuHelperEntry()

    init {
        load()
        observeUpdateProgress()
        checkSelfUpdateBanner()
    }

    // ── Update actions ───────────────────────────────────────────────────────

    /**
     * Force re-check for all installed entries. Clears cached timing gate.
     * Suitable for a "Check now" affordance in the header.
     */
    fun checkForUpdates() {
        viewModelScope.launch {
            try {
                updateRepository.checkNow(force = true)
            } catch (e: Throwable) {
                _userMessage.emit("Check failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /**
     * Download + install all entries that currently have [UpdateInfo.hasUpdate] = true.
     * Runs sequentially (PackageInstaller requires serial sessions).
     */
    fun updateAll() {
        viewModelScope.launch {
            try {
                updateRepository.updateAll()
            } catch (e: Throwable) {
                _userMessage.emit("Update all failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /**
     * Update a single entry via the update repository, tracking progress in
     * [updateProgressMap]. Distinct from the legacy [update] path which uses
     * the source router directly — repository path also updates persisted state.
     */
    fun updateViaRepository(entryId: String) {
        viewModelScope.launch {
            try {
                updateRepository.updateOne(entryId)
            } catch (e: Throwable) {
                _userMessage.emit("Update failed: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                refresh()
            }
        }
    }

    /** Fan-out [UpdateRepository.updateProgressFlow] into the per-id map. */
    private fun observeUpdateProgress() {
        viewModelScope.launch {
            updateRepository.updateProgressFlow.collect { progress ->
                // All progress states (in-flight and terminal) simply update the map.
                // Terminal states (Done/Cancelled/Failed) are added here and removed
                // below after a short delay so the UI can render the final state briefly.
                _updateProgressMap.update { current ->
                    current + (progress.entryId to progress)
                }

                // Clear terminal states after a short delay.
                // Guard: only remove if the entry is still in a terminal state —
                // a rapid re-update of the same entry within the 1.5s window would
                // otherwise delete the NEW in-flight entry.
                if (progress is UpdateProgress.Done ||
                    progress is UpdateProgress.Cancelled ||
                    progress is UpdateProgress.Failed
                ) {
                    val id = progress.entryId
                    kotlinx.coroutines.delay(1_500)
                    _updateProgressMap.update { current ->
                        val cur = current[id]
                        if (cur is UpdateProgress.Done ||
                            cur is UpdateProgress.Cancelled ||
                            cur is UpdateProgress.Failed
                        ) {
                            current - id
                        } else {
                            current  // a new in-flight update started — leave it alone
                        }
                    }
                    refresh()
                }
            }
        }
    }

    // ── Self-update banner actions ────────────────────────────────────────────

    /**
     * Launch the 24h-gated banner check on init. Silent: failures are swallowed
     * because this runs in the background — the user didn't ask for it.
     */
    private fun checkSelfUpdateBanner() {
        viewModelScope.launch {
            try {
                val result = selfUpdateRepository.bannerState()
                // Only surface Available; UpToDate and Failed are silent on launch.
                if (result is SelfUpdateResult.Available) {
                    _selfUpdate.value = result
                }
            } catch (_: Throwable) {
                // Background check: swallow errors silently.
            }
        }
    }

    /**
     * Persist [version] as the user's "skip this version" choice and clear the
     * banner for this session. The 24h-gated [bannerState] will suppress this
     * version on all future launch checks until a new release appears.
     */
    fun skipSelfUpdate(version: String) {
        viewModelScope.launch {
            selfUpdateRepository.skipVersion(version)
            _selfUpdate.value = null
            _selfUpdateSheet.value = null
        }
    }

    /**
     * Hide the banner for this session without persisting the skip. The banner
     * will reappear on the next app launch (subject to the 24h gate).
     */
    fun dismissSelfUpdateBanner() {
        _selfUpdate.value = null
    }

    /**
     * Open the "What's new" sheet for the current [SelfUpdateResult.Available].
     * No-op if [selfUpdate] is not Available.
     */
    fun openSelfUpdateSheet() {
        val available = _selfUpdate.value as? SelfUpdateResult.Available ?: return
        _selfUpdateSheet.value = SelfUpdateSheetUiState.Available(
            version   = available.version,
            changelog = available.changelogMarkdown,
            apkUrl    = available.apkUrl,
            sha256Url = available.sha256Url,
        )
    }

    /**
     * Start downloading + installing the APK at [apkUrl]. Mirrors
     * [AboutViewModel.downloadAndInstall]: stores the [Job] in
     * [selfUpdateDownloadJob] so [dismissSelfUpdateSheet] can cancel it.
     */
    fun startSelfUpdateDownload(apkUrl: String) {
        // Resolve sha256Url from the current sheet state while it's still Available.
        val sha256Url = (_selfUpdateSheet.value as? SelfUpdateSheetUiState.Available)?.sha256Url

        selfUpdateDownloadJob = viewModelScope.launch {
            try {
                selfUpdateRepository.downloadAndInstall(apkUrl, sha256Url).collect { progress ->
                    when (progress) {
                        is ApkDownloader.Progress.Started ->
                            _selfUpdateSheet.value = SelfUpdateSheetUiState.Downloading(0)
                        is ApkDownloader.Progress.Chunk -> {
                            val pct = if (progress.totalBytes > 0L) {
                                (progress.downloaded * 100 / progress.totalBytes).toInt()
                            } else 0
                            _selfUpdateSheet.value = SelfUpdateSheetUiState.Downloading(pct)
                        }
                        is ApkDownloader.Progress.Done -> {
                            // System installer launched; close the sheet and clear the banner.
                            _selfUpdateSheet.value = null
                            _selfUpdate.value = null
                        }
                        is ApkDownloader.Progress.Failed ->
                            _userMessage.emit("Update failed: ${progress.message}")
                    }
                }
            } catch (e: Throwable) {
                _userMessage.emit("Update failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /**
     * Dismiss the self-update bottom sheet. Cancels any in-flight download so
     * the system installer is never launched after the user walks away.
     */
    fun dismissSelfUpdateSheet() {
        selfUpdateDownloadJob?.cancel()
        selfUpdateDownloadJob = null
        _selfUpdateSheet.value = null
    }

    /**
     * UI state for the dashboard self-update bottom sheet.
     *
     * Mirrors [AboutViewModel.SelfUpdateUiState] but lives here so the
     * dashboard doesn't depend on the About module.
     */
    sealed interface SelfUpdateSheetUiState {
        data class Available(
            val version   : String,
            val changelog : String,
            val apkUrl    : String,
            val sha256Url : String?,
        ) : SelfUpdateSheetUiState
        data class Downloading(val percent: Int) : SelfUpdateSheetUiState
    }

    // ── EmuHelper ────────────────────────────────────────────────────────────

    /** Hand-built [AppEntry] for EmuHelper (not part of the Obtainium pack). */
    private fun emuHelperEntry() = AppEntry(
        id = "io.github.mayusi.emuhelper",
        name = "EmuHelper",
        author = "mayusi",
        about = "A configurable, source-agnostic Android download manager for user-supplied web archive endpoints.",
        sourceUrl = "https://github.com/mayusi/EmuHelper",
        source = SourceKind.GITHUB,
        apkFilterRegEx = "",
        invertApkFilter = false,
        autoFilterByArch = true,
        includePrereleases = false,
        fallbackToOlderReleases = false,
        versionExtractionRegEx = "",
        filterReleaseTitlesRegEx = "",
        categories = listOf("Utilities"),
        trackOnly = false,
        system = SystemTag.UTILITY,
        recommended = true,
    )

    /** Whether EmuHelper is currently installed (queried on demand). */
    fun isEmuHelperInstalled(): Boolean =
        emuHelper.id in installed.snapshot()

    /** Download + install the EmuHelper APK. Mirrors [update]'s APK path. */
    fun installEmuHelper() {
        if (_emuHelperInstalling.value) return
        _emuHelperInstalling.value = true
        viewModelScope.launch {
            try {
                val resolved = router.resolve(emuHelper)
                if (resolved !is ResolveResult.Found) {
                    _userMessage.emit("Install failed: could not resolve EmuHelper")
                    return@launch
                }
                var file: File? = null
                var downloadError: String? = null
                downloader.download(resolved.apkUrl, resolved.filename).collect { p ->
                    when (p) {
                        is ApkDownloader.Progress.Done -> file = p.file
                        is ApkDownloader.Progress.Failed -> downloadError = p.message
                        else -> Unit
                    }
                }
                if (downloadError != null) {
                    _userMessage.emit("Install failed: $downloadError")
                    return@launch
                }
                val f = file ?: run {
                    _userMessage.emit("Install failed: download did not complete")
                    return@launch
                }
                if (resolved.kind == AssetKind.APK) {
                    installer.install(f)
                    _userMessage.emit("Installed EmuHelper")
                }
            } catch (e: Throwable) {
                _userMessage.emit("Install failed: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                _emuHelperInstalling.value = false
                refresh()
            }
        }
    }

    // ── Lifecycle / data loading ─────────────────────────────────────────────

    /** Public refresh — call from ON_RESUME. */
    fun refresh() {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            try {
                val dualScreen = setupOptions.isDualScreen.first()
                val entries = withContext(Dispatchers.IO) {
                    val all = if (dualScreen) parser.loadDualScreen() else parser.loadStandard()
                    all.filterNot { it.id == "904332840" }
                }
                val installedNow = installed.snapshot()
                val (have, dont) = entries.partition { it.id in installedNow }
                val sorted = have.sortedBy { it.name.lowercase() } +
                    dont.sortedBy { it.name.lowercase() }

                _state.update {
                    UiState.Ready(
                        entries = sorted,
                        installedIds = installedNow,
                    )
                }
            } catch (t: Throwable) {
                _state.update { UiState.Failed(t.message ?: t.javaClass.simpleName) }
            }
        }
    }

    // ── Per-app actions (legacy path — direct source router) ─────────────────

    /**
     * Download + install a single entry (used for AvailableCard "Install" action
     * and any fresh-install path). Does NOT go through [UpdateRepository], so the
     * persisted update badge is NOT cleared — for installed apps whose badge must
     * clear, use [updateViaRepository] instead (FIX 3 / FIX 4).
     *
     * Renamed from `update` to `downloadAndInstall` to remove the misleading
     * "update" naming — this function performs download+install regardless of
     * whether a newer version actually exists.
     */
    fun downloadAndInstall(entry: AppEntry) {
        if (entry.id in _busy.value) return
        _busy.update { it + entry.id }
        viewModelScope.launch {
            try {
                val rootPath = storage.rootPath.first() ?: storage.defaultPath

                val resolved = router.resolve(entry)
                if (resolved !is ResolveResult.Found) {
                    _userMessage.emit("Install failed: could not resolve ${entry.name}")
                    return@launch
                }

                var file: File? = null
                var downloadError: String? = null
                downloader.download(resolved.apkUrl, resolved.filename).collect { p ->
                    when (p) {
                        is ApkDownloader.Progress.Done -> file = p.file
                        // FIX 5: surface the real downloader failure message instead of
                        // the generic "download did not complete" fallback.
                        is ApkDownloader.Progress.Failed -> downloadError = p.message
                        else -> Unit
                    }
                }
                if (downloadError != null) {
                    _userMessage.emit("Install failed: $downloadError")
                    return@launch
                }
                val f = file ?: run {
                    _userMessage.emit("Install failed: download did not complete")
                    return@launch
                }

                when (resolved.kind) {
                    io.github.mayusi.emutran.data.source.AssetKind.DRIVER_ZIP -> {
                        // FIX 2: use the shared resolveTurnipDir helper so rootPath
                        // values that already end in "/Emulation" don't produce the
                        // double-suffix path .../Emulation/Emulation/tools/turnip.
                        val turnipDir = resolveTurnipDir(rootPath).apply { mkdirs() }
                        f.copyTo(File(turnipDir, f.name), overwrite = true)
                        _userMessage.emit("Installed ${entry.name}")
                    }
                    io.github.mayusi.emutran.data.source.AssetKind.APK -> {
                        installer.install(f)
                        _userMessage.emit("Installed ${entry.name}")
                    }
                }
            } catch (e: Throwable) {
                _userMessage.emit("Install failed: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                _busy.update { it - entry.id }
                refresh()
            }
        }
    }

    /** Open Android's system uninstall dialog for [entry]. */
    fun uninstall(entry: AppEntry) {
        try {
            uninstaller.requestUninstall(entry.id)
            _userMessage.tryEmit("Uninstalled ${entry.name}")
        } catch (e: Throwable) {
            _userMessage.tryEmit("Uninstall failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    sealed interface UiState {
        data object Loading : UiState
        data class Ready(
            val entries: List<AppEntry>,
            val installedIds: Set<String>,
        ) : UiState
        data class Failed(val message: String) : UiState
    }
}
