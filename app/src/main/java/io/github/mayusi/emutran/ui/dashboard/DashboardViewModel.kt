package io.github.mayusi.emutran.ui.dashboard

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.mayusi.emutran.data.device.GpuDetector
import io.github.mayusi.emutran.data.device.GpuInfo
import io.github.mayusi.emutran.data.device.InstalledAppsRegistry
import io.github.mayusi.emutran.data.manifest.AppEntry
import io.github.mayusi.emutran.data.manifest.ManifestDiffStore
import io.github.mayusi.emutran.data.manifest.ObtainiumPackParser
import io.github.mayusi.emutran.data.manifest.PendingPackDiff
import io.github.mayusi.emutran.data.manifest.SourceKind
import io.github.mayusi.emutran.data.manifest.SystemTag
import io.github.mayusi.emutran.data.selection.SelectedAppsStore
import io.github.mayusi.emutran.data.source.AppSourceRouter
import io.github.mayusi.emutran.data.source.AssetKind
import io.github.mayusi.emutran.data.source.ResolveResult
import io.github.mayusi.emutran.data.storage.SetupOptionsStore
import io.github.mayusi.emutran.data.storage.StorageRootStore
import io.github.mayusi.emutran.data.update.SelfUpdateProgress
import io.github.mayusi.emutran.data.update.SelfUpdateRepository
import io.github.mayusi.emutran.data.update.SelfUpdateResult
import io.github.mayusi.emutran.data.update.UpdateInfo
import io.github.mayusi.emutran.data.update.UpdateProgress
import io.github.mayusi.emutran.data.update.UpdateRepository
import io.github.mayusi.emutran.domain.download.ApkDownloader
import io.github.mayusi.emutran.domain.drivers.DriverHintProvider
import io.github.mayusi.emutran.domain.install.InstallerRouter
import io.github.mayusi.emutran.domain.install.Uninstaller
import io.github.mayusi.emutran.domain.scaffold.resolveTurnipDir
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
 * FIX 3b: [refresh] is debounced — rapid successive calls coalesce into
 *   one load() so updateAll()'s N terminal events don't fire N page reloads.
 *
 * FIX 5: [emuHelperInstalled] is a StateFlow updated via a coroutine on
 *   resume-trigger, removing the synchronous PM call from the composition thread.
 *   [installEmuHelper] delegates to [downloadAndInstall].
 *   The "904332840" inline string is replaced with [ObtainiumPackParser.OBTAINIUM_META_ENTRY_ID].
 *   [checkSelfUpdateBanner] logs failures to Logcat.
 *   [startSelfUpdateDownload] throttles progress emissions to ~200ms intervals.
 *   CancellationException from a cancelled download is not surfaced as a failure snackbar.
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
    private val driverHintProvider: DriverHintProvider,
    private val gpuDetector: GpuDetector,
    private val manifestDiffStore: ManifestDiffStore,
) : ViewModel() {

    private val _state = MutableStateFlow<UiState>(UiState.Loading)
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Per-app action in-flight tracking so the UI can disable buttons. */
    private val _busy = MutableStateFlow<Set<String>>(emptySet())
    val busy: StateFlow<Set<String>> = _busy.asStateFlow()

    /** User-facing messages for update/install/uninstall actions (success or failure). */
    private val _userMessage = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val userMessage: SharedFlow<String> = _userMessage.asSharedFlow()

    // ── Self-update banner ───────────────────────────────────────────────────

    private val _selfUpdate = MutableStateFlow<SelfUpdateResult?>(null)
    val selfUpdate: StateFlow<SelfUpdateResult?> = _selfUpdate.asStateFlow()

    private val _selfUpdateSheet = MutableStateFlow<SelfUpdateSheetUiState?>(null)
    val selfUpdateSheet: StateFlow<SelfUpdateSheetUiState?> = _selfUpdateSheet.asStateFlow()

    /** Active download job so [dismissSelfUpdateSheet] can cancel it. */
    private var selfUpdateDownloadJob: Job? = null

    // ── Update-repository wiring ─────────────────────────────────────────────

    val updateState: StateFlow<Map<String, UpdateInfo>> = updateRepository.updateState()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val updateCount: StateFlow<Int> = updateRepository.updateCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _updateProgressMap = MutableStateFlow<Map<String, UpdateProgress>>(emptyMap())
    val updateProgressMap: StateFlow<Map<String, UpdateProgress>> = _updateProgressMap.asStateFlow()

    /** The featured EmuHelper card's app entry. */
    val emuHelper: AppEntry = emuHelperEntry()

    // FIX 5: emuHelperInstalled is a StateFlow updated off the main thread.
    // Screens collect this instead of calling the blocking snapshot() in composition.
    private val _emuHelperInstalled = MutableStateFlow(false)
    val emuHelperInstalled: StateFlow<Boolean> = _emuHelperInstalled.asStateFlow()

    // FIX 5: emuHelperInstalling kept as StateFlow so UI can disable the button.
    private val _emuHelperInstalling = MutableStateFlow(false)
    val emuHelperInstalling: StateFlow<Boolean> = _emuHelperInstalling.asStateFlow()

    // FIX 3b: debounce refresh — cancel-and-relaunch so rapid successive calls
    // (e.g. N Done events from updateAll) coalesce into one load().
    private var refreshJob: Job? = null

    // ── Per-emulator driver hints ────────────────────────────────────────────

    /**
     * Cached GPU snapshot, resolved once via the suspend [GpuDetector.snapshot]
     * (the EGL probe is expensive and must not run on the main thread). Null
     * until [resolveDriverHints] completes; lookups before then yield no hint.
     */
    @Volatile private var gpuSnapshot: GpuInfo? = null

    /**
     * Map of emulator appId -> one-line driver hint, recomputed whenever the
     * entry list or the GPU snapshot changes. Populated only when the device
     * GPU is Adreno AND the entry is a known emulator; otherwise the appId is
     * absent and the UI renders no hint line (so card layout is unchanged).
     */
    private val _driverHints = MutableStateFlow<Map<String, String>>(emptyMap())
    val driverHints: StateFlow<Map<String, String>> = _driverHints.asStateFlow()

    // ── Manifest "what's new" banner ─────────────────────────────────────────

    /**
     * Pending catalog diff to surface as a dismissible banner. Null when there
     * is nothing new (first launch, no-change refresh, or already dismissed).
     */
    val pendingPackDiff: StateFlow<PendingPackDiff?> = manifestDiffStore.pendingDiff
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        load()
        observeUpdateProgress()
        checkSelfUpdateBanner()
        refreshEmuHelperInstalled()
        resolveDriverHints()
    }

    // ── Update actions ───────────────────────────────────────────────────────

    fun checkForUpdates() {
        viewModelScope.launch {
            try {
                updateRepository.checkNow(force = true)
            } catch (e: Throwable) {
                _userMessage.emit("Check failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    fun updateAll() {
        viewModelScope.launch {
            try {
                updateRepository.updateAll()
            } catch (e: Throwable) {
                _userMessage.emit("Update all failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

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
                _updateProgressMap.update { current ->
                    current + (progress.entryId to progress)
                }

                if (progress is UpdateProgress.Done ||
                    progress is UpdateProgress.Cancelled ||
                    progress is UpdateProgress.Failed
                ) {
                    val id = progress.entryId
                    delay(1_500)
                    _updateProgressMap.update { current ->
                        val cur = current[id]
                        if (cur is UpdateProgress.Done ||
                            cur is UpdateProgress.Cancelled ||
                            cur is UpdateProgress.Failed
                        ) {
                            current - id
                        } else {
                            current
                        }
                    }
                    refresh()
                }
            }
        }
    }

    // ── Self-update banner actions ────────────────────────────────────────────

    /**
     * Launch the 24h-gated banner check on init.
     * FIX 5: log failures to Logcat so background issues are debuggable.
     */
    private fun checkSelfUpdateBanner() {
        viewModelScope.launch {
            try {
                val result = selfUpdateRepository.bannerState()
                if (result is SelfUpdateResult.Available) {
                    _selfUpdate.value = result
                }
            } catch (e: Throwable) {
                // FIX 5: log background failures so they're visible in Logcat.
                Log.d(TAG, "checkSelfUpdateBanner failed: ${e.message ?: e.javaClass.simpleName}", e)
            }
        }
    }

    fun skipSelfUpdate(version: String) {
        viewModelScope.launch {
            selfUpdateRepository.skipVersion(version)
            _selfUpdate.value = null
            _selfUpdateSheet.value = null
        }
    }

    fun dismissSelfUpdateBanner() {
        _selfUpdate.value = null
    }

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
     * Start downloading + installing the self-update APK.
     *
     * FIX 4 (throttle): progress emissions are throttled to ~200ms intervals so
     * every 64 KB chunk doesn't re-compose the sheet (which re-parses markdown).
     *
     * FIX 4 (version-in-title): the Downloading state carries the version string
     * so the title "What's new in vX" doesn't lose its version mid-download.
     *
     * FIX 4 (CancellationException): when the user dismisses the sheet mid-download
     * the job is cancelled; we catch CancellationException and reset state silently
     * instead of emitting a "Update failed" snackbar.
     */
    fun startSelfUpdateDownload(apkUrl: String) {
        val currentSheet = _selfUpdateSheet.value as? SelfUpdateSheetUiState.Available ?: return
        val sha256Url = currentSheet.sha256Url
        val version = currentSheet.version

        selfUpdateDownloadJob = viewModelScope.launch {
            try {
                var lastEmitMs = 0L
                selfUpdateRepository.downloadAndInstall(apkUrl, sha256Url).collect { progress ->
                    when (progress) {
                        is SelfUpdateProgress.Started ->
                            _selfUpdateSheet.value = SelfUpdateSheetUiState.Downloading(0, version)
                        is SelfUpdateProgress.Chunk -> {
                            // FIX 4 throttle: emit at most once per 200ms.
                            val now = System.currentTimeMillis()
                            if (now - lastEmitMs >= DOWNLOAD_EMIT_THROTTLE_MS) {
                                lastEmitMs = now
                                val pct = if (progress.totalBytes > 0L) {
                                    (progress.downloaded * 100 / progress.totalBytes).toInt()
                                } else 0
                                _selfUpdateSheet.value = SelfUpdateSheetUiState.Downloading(pct, version)
                            }
                        }
                        is SelfUpdateProgress.Done -> {
                            _selfUpdateSheet.value = null
                            _selfUpdate.value = null
                        }
                        // DEFECT 1: the OS blocked the install because "Install unknown
                        // apps" is off for EmuTran. The dashboard surfaces user messages
                        // through a plain-string snackbar (the screen collects
                        // [userMessage]), so we both tell the user what's wrong AND
                        // deep-link them straight to the settings page. The sheet is
                        // closed; after granting they re-open it and tap "Update now".
                        is SelfUpdateProgress.NeedsInstallPermission -> {
                            _selfUpdateSheet.value = null
                            _userMessage.emit(
                                "Allow EmuTran to install apps in the settings page that " +
                                    "just opened, then tap Update again."
                            )
                            selfUpdateRepository.openInstallPermissionSettings()
                        }
                        is SelfUpdateProgress.Failed ->
                            _userMessage.emit("Update failed: ${progress.message}")
                    }
                }
            } catch (e: CancellationException) {
                // FIX 4: user cancelled via dismissSelfUpdateSheet — reset state silently.
                _selfUpdateSheet.value = null
                throw e  // rethrow for structured concurrency
            } catch (e: Throwable) {
                _userMessage.emit("Update failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    fun dismissSelfUpdateSheet() {
        selfUpdateDownloadJob?.cancel()
        selfUpdateDownloadJob = null
        _selfUpdateSheet.value = null
    }

    /**
     * UI state for the dashboard self-update bottom sheet.
     */
    sealed interface SelfUpdateSheetUiState {
        data class Available(
            val version   : String,
            val changelog : String,
            val apkUrl    : String,
            val sha256Url : String?,
        ) : SelfUpdateSheetUiState
        // FIX 4: carry version into Downloading so the title stays correct.
        data class Downloading(val percent: Int, val version: String) : SelfUpdateSheetUiState
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

    /**
     * FIX 5: refresh emuHelperInstalled off the main thread via coroutine.
     * Called on init and on resume via [onResume].
     */
    private fun refreshEmuHelperInstalled() {
        viewModelScope.launch {
            installed.refresh()
            _emuHelperInstalled.value = emuHelper.id in installed.snapshot()
        }
    }

    /** Called by the screen on ON_RESUME to refresh installed state. */
    fun onResume() {
        refresh()
        refreshEmuHelperInstalled()
    }

    // ── Driver hints / manifest diff ─────────────────────────────────────────

    /**
     * Resolve the GPU snapshot once (off the main thread via the suspend
     * [GpuDetector.snapshot]) and recompute the per-emulator driver hints for
     * the currently-loaded entries.
     *
     * For non-Adreno GPUs every [DriverHintProvider.hintFor] returns null, so
     * the map stays empty and the UI renders no hint lines. The expensive EGL
     * probe runs only once thanks to GpuDetector's internal cache.
     */
    private fun resolveDriverHints() {
        viewModelScope.launch {
            try {
                gpuSnapshot = gpuDetector.snapshot()
                recomputeDriverHints()
            } catch (e: Throwable) {
                Log.d(TAG, "resolveDriverHints failed: ${e.message ?: e.javaClass.simpleName}", e)
            }
        }
    }

    /**
     * Rebuild [_driverHints] from the current entries and cached GPU snapshot.
     * No-op (empty map) until the snapshot is resolved or when the GPU is not
     * Adreno. Called after each [load] and once the snapshot lands.
     */
    private fun recomputeDriverHints() {
        val gpu = gpuSnapshot ?: return
        val entries = (_state.value as? UiState.Ready)?.entries ?: return
        _driverHints.value = buildMap {
            for (entry in entries) {
                driverHintProvider.hintFor(entry.id, gpu)?.let { put(entry.id, it) }
            }
        }
    }

    /** Dismiss the manifest "what's new" banner. */
    fun dismissPackDiff() {
        viewModelScope.launch {
            manifestDiffStore.clearPendingDiff()
        }
    }

    /**
     * FIX 5: installEmuHelper delegates to downloadAndInstall(emuHelper) so
     * there is one shared download+install path instead of duplicated logic.
     */
    fun installEmuHelper() {
        if (_emuHelperInstalling.value) return
        _emuHelperInstalling.value = true
        viewModelScope.launch {
            try {
                downloadAndInstall(emuHelper)
            } finally {
                _emuHelperInstalling.value = false
            }
        }
    }

    // ── Lifecycle / data loading ─────────────────────────────────────────────

    /**
     * FIX 3b: debounced refresh — cancel the previous pending load and
     * wait 50ms before launching a new one. Rapid successive calls (e.g. the
     * N Done events from updateAll) coalesce into a single load().
     */
    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            delay(REFRESH_DEBOUNCE_MS)
            load()
        }
    }

    private fun load() {
        viewModelScope.launch {
            try {
                val dualScreen = setupOptions.isDualScreen.first()
                val entries = withContext(Dispatchers.IO) {
                    val all = if (dualScreen) parser.loadDualScreen() else parser.loadStandard()
                    // FIX 5: use the constant instead of the inline "904332840" string.
                    all.filterNot { it.id == ObtainiumPackParser.OBTAINIUM_META_ENTRY_ID }
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
                // Keep driver hints in sync with the freshly-loaded entry set.
                recomputeDriverHints()
            } catch (t: Throwable) {
                _state.update { UiState.Failed(t.message ?: t.javaClass.simpleName) }
            }
        }
    }

    // ── Per-app actions ──────────────────────────────────────────────────────

    /**
     * Download + install a single entry. Used for AvailableCard "Install"
     * and as the shared path for [installEmuHelper] (FIX 5).
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

    companion object {
        private const val TAG = "DashboardViewModel"
        /** Debounce window for refresh() calls: 50ms. */
        private const val REFRESH_DEBOUNCE_MS = 50L
        /** Minimum interval between Downloading state emissions: ~200ms. */
        private const val DOWNLOAD_EMIT_THROTTLE_MS = 200L
    }
}
