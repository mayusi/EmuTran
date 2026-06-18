package io.github.mayusi.emutran.ui.progress

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.mayusi.emutran.data.device.GpuDetector
import io.github.mayusi.emutran.data.device.InstalledAppsRegistry
import io.github.mayusi.emutran.data.device.NetworkChecker
import io.github.mayusi.emutran.data.manifest.AppEntry
import io.github.mayusi.emutran.data.manifest.ObtainiumPackParser
import io.github.mayusi.emutran.data.selection.SelectedAppsStore
import io.github.mayusi.emutran.data.source.AppSourceRouter
import io.github.mayusi.emutran.data.source.ResolveResult
import io.github.mayusi.emutran.data.storage.FileFolderBuilder
import io.github.mayusi.emutran.data.storage.SetupOptionsStore
import io.github.mayusi.emutran.data.storage.StorageRootStore
import io.github.mayusi.emutran.domain.download.ApkDownloader
import io.github.mayusi.emutran.domain.drivers.DriverStager
import io.github.mayusi.emutran.domain.install.InstallResult
import io.github.mayusi.emutran.domain.install.InstallerRouter
import io.github.mayusi.emutran.domain.scaffold.FolderSpec
import io.github.mayusi.emutran.domain.scaffold.resolveTurnipDir   // FIX 5
import io.github.mayusi.emutran.service.SetupForegroundService
import io.github.mayusi.emutran.service.SetupServiceBridge          // FIX 4
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

/**
 * Drives the full Setup → Done flow:
 *
 *   1. Scaffold the folder tree.
 *   2. Resolve every picked entry to a download URL.
 *   3. Download all of them sequentially (simple, predictable progress).
 *   4. Install all of them sequentially via system intent
 *      (Android won't stack install dialogs anyway).
 *   5. Skip anything already installed so the user doesn't see redundant
 *      install dialogs for apps they already have.
 *
 * Per-app failures (download error, resolve error, asset not found) do
 * NOT abort the whole run — we log them and surface a summary at the end.
 * Shizuku silent install is used automatically when Shizuku is installed
 * and granted; otherwise each install needs a tap.
 */
@HiltViewModel
class ProgressViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: StorageRootStore,
    private val builder: FileFolderBuilder,
    private val parser: ObtainiumPackParser,
    private val selectedStore: SelectedAppsStore,
    private val router: AppSourceRouter,
    private val downloader: ApkDownloader,
    private val installer: InstallerRouter,
    private val installed: InstalledAppsRegistry,
    private val gpuDetector: GpuDetector,
    private val driverStager: DriverStager,
    private val setupOptions: SetupOptionsStore,
    private val networkChecker: NetworkChecker,
    private val bridge: SetupServiceBridge,             // FIX 4
) : ViewModel() {

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    // Retry support. We remember the entries that failed (the AppEntry
    // objects, not just names) plus the run context so retryFailed() can
    // re-run only those without re-scaffolding or re-staging drivers.
    private var failedEntries: List<AppEntry> = emptyList()
    private var lastRootPath: String? = null
    private var lastSkipped: List<String> = emptyList()
    private var retrying = false

    /**
     * FIX 2: holds the coroutine running [runPipeline] so we can cancel it
     * from [cancelAndReset] and [retryAll] without waiting for the viewModelScope
     * to be cleared. Nulled after the job completes or is cancelled.
     */
    private var pipelineJob: Job? = null

    /**
     * Non-null while the pipeline is suspended waiting for the user to
     * decide whether to continue without a network connection.
     * Resolved by [confirmOffline]; completing with `true` proceeds to the
     * download phase, `false` skips it (folders-only run).
     */
    private var offlineDecision: CompletableDeferred<Boolean>? = null

    /**
     * Called by [ProgressScreen] when the user taps "Continue anyway" or
     * "Cancel" on the offline pre-flight dialog.
     *
     * FIX 2: guard against a stale / already-completed deferred — if the
     * pipeline was cancelled while we were suspended at OfflineWarning, the
     * deferred will have been cancelled already and this is a no-op.
     *
     * @param continueAnyway true → proceed with downloads (they will fail per-app,
     *   existing error handling in processEntries() applies); false → skip the
     *   download/install phases and go directly to a Done state showing folders built.
     */
    fun confirmOffline(continueAnyway: Boolean) {
        offlineDecision?.takeIf { !it.isCompleted }?.complete(continueAnyway)
    }

    fun start() {
        if (_state.value !is State.Idle) return
        startSetupService()
        pipelineJob = viewModelScope.launch { runPipeline() }   // FIX 2
    }

    /**
     * Reset to Idle and re-run the full pipeline from scratch.
     * Used by the Failed-state "Retry" button in ProgressScreen.
     *
     * FIX 2: cancel the previous pipeline job before launching a new one
     * so no zombie coroutine lingers.
     */
    fun retryAll() {
        if (_state.value is State.Idle) return
        // FIX 2: kill the old pipeline (and any suspended offlineDecision).
        cancelPipelineJob()
        _state.update { State.Idle }
        bridge.mutable.value = State.Idle                        // FIX 4
        startSetupService()
        pipelineJob = viewModelScope.launch { runPipeline() }   // FIX 2
    }

    /**
     * Cancel the running pipeline and emit [State.Cancelled] so the service
     * stops immediately.
     *
     * FIX 1: emit Cancelled (a proper terminal state) instead of Idle so
     * [SetupForegroundService] calls stopSelf() and dismisses the notification.
     *
     * FIX 2: cancel [pipelineJob] and clean up [offlineDecision] before
     * emitting the terminal state so no zombie coroutine can resurrect the
     * pipeline later.
     *
     * The call site in [ProgressScreen]'s cancel dialog calls [onGoToDashboard]
     * immediately after this — that navigation still works regardless of state.
     */
    fun cancelAndReset() {
        cancelPipelineJob()                         // FIX 2
        _state.update { State.Cancelled }
        bridge.mutable.value = State.Cancelled      // FIX 4
    }

    /**
     * FIX 2 helper: cancel the running pipeline job and discard any pending
     * offline decision so confirmOffline() can't resurrect a dead pipeline.
     */
    private fun cancelPipelineJob() {
        pipelineJob?.cancel()
        pipelineJob = null
        // Cancel or complete the deferred so the suspended coroutine
        // (already cancelled via pipelineJob.cancel()) is properly cleaned up.
        offlineDecision?.cancel()
        offlineDecision = null
    }

    /**
     * Re-run resolve → download → install for ONLY the entries that
     * failed last time. Newly-succeeded entries move to the installed
     * list; entries that still fail stay in failed. Does not re-scaffold
     * folders or re-stage drivers — those already ran.
     *
     * FIX 3: added catch for non-cancellation exceptions so a crash in
     * [finishWithDrivers] doesn't silently leave the service running forever.
     */
    fun retryFailed() {
        if (retrying) return
        val toRetry = failedEntries
        val root = lastRootPath
        if (toRetry.isEmpty() || root == null) return
        // Carry forward whatever was already installed so the merged Done
        // state keeps everything from the original run.
        val priorInstalled = (_state.value as? State.Done)?.installed.orEmpty()
        retrying = true
        startSetupService()
        viewModelScope.launch {
            try {
                val result = processEntries(toRetry, root)
                failedEntries = result.failedEntries
                finishWithDrivers(
                    rootPath = root,
                    installedNames = priorInstalled + result.installedNames,
                    skipped = lastSkipped,
                    failures = result.failures,
                )
            } catch (t: Throwable) {
                // FIX 3: rethrow CancellationException so structured concurrency
                // is not broken; emit Failed for everything else so the service
                // gets a terminal state and can stop itself.
                if (t is CancellationException) throw t
                emitState(State.Failed(t.message ?: "Unexpected error during retry", emptyList()))
            } finally {
                retrying = false
            }
        }
    }

    // ---- internal helpers ----

    /** Emit to both the VM's own flow and the bridge simultaneously. */
    private fun emitState(s: State) {
        _state.update { s }
        bridge.mutable.value = s
    }

    private suspend fun runPipeline() {
        try {
            runPipelineInternal()
        } catch (t: Throwable) {
            // FIX 3 coverage for runPipeline: propagate cancellation, surface
            // everything else as a Failed terminal state so the service stops.
            if (t is CancellationException) throw t
            emitState(State.Failed(t.message ?: "Unexpected pipeline error", emptyList()))
        }
    }

    private suspend fun runPipelineInternal() {
        // --- Phase 1: scaffold ---
        val rootPath = store.rootPath.first() ?: store.defaultPath
        // Pass the PARENT of Emulation/ as the builder root, because
        // FolderSpec.tree entries already start with "Emulation/...".
        // If the user picked /sdcard/Emulation as their root, we strip
        // the trailing Emulation so we don't create /sdcard/Emulation/
        // Emulation/roms/... — that's the double-suffix bug.
        val rootFile = File(rootPath)
        val builderRoot = if (rootFile.name.equals("Emulation", ignoreCase = true)) {
            rootFile.parentFile ?: rootFile
        } else {
            rootFile
        }
        var foldersDone = 0
        var foldersTotal = FolderSpec.tree.size + FolderSpec.biosReadmes.size
        emitState(State.Scaffolding(0, foldersTotal, "Building folder structure…"))
        builder.build(builderRoot, FolderSpec.tree, FolderSpec.biosReadmes).collect { p ->
            when (p) {
                is FileFolderBuilder.Progress.Started ->
                    foldersTotal = p.total
                is FileFolderBuilder.Progress.Step -> {
                    foldersDone = p.done
                    emitState(State.Scaffolding(foldersDone, p.total, p.label))
                }
                is FileFolderBuilder.Progress.Finished -> Unit
                is FileFolderBuilder.Progress.Failed -> {
                    emitState(State.Failed(p.message, emptyList()))
                    return@collect
                }
            }
        }
        if (_state.value is State.Failed) return

        // --- Phase 2: read the picker selection + manifest ---
        val pickedIds = selectedStore.pickedIds.first()
        if (pickedIds.isEmpty()) {
            finishWithDrivers(rootPath, emptyList(), emptyList(), emptyList())
            return
        }
        // Load the same manifest the picker used so resolved ids line up
        // with what the user actually selected (dual-screen forks differ).
        val dualScreen = setupOptions.isDualScreen.first()
        val allEntries = if (dualScreen) parser.loadDualScreen() else parser.loadStandard()
        val entryById = allEntries.associateBy { it.id }
        val picked: List<AppEntry> = pickedIds.mapNotNull { entryById[it] }
        // FIX 2: refresh the InstalledAppsRegistry cache on IO dispatcher before
        // calling snapshot() so the PackageManager query never runs on main thread.
        installed.refresh()
        val installedNow = installed.snapshot()

        // Anything already installed gets skipped silently (user can
        // explicitly re-pick on the next run if they want to update).
        val toProcess = picked.filter { it.id !in installedNow }
        val skippedAlreadyInstalled = picked.filter { it.id in installedNow }.map { it.name }

        // Remember run context so retryFailed() can re-run only the
        // failures later without re-scaffolding or re-staging drivers.
        lastRootPath = rootPath
        lastSkipped = skippedAlreadyInstalled

        // --- Pre-flight offline check ---
        // Only warn when there are apps to download. A folders-only run
        // (toProcess is empty after the already-installed filter) is
        // perfectly fine offline and does not need a warning.
        if (toProcess.isNotEmpty() && !networkChecker.isOnline()) {
            val decision = CompletableDeferred<Boolean>()
            offlineDecision = decision
            emitState(State.OfflineWarning)
            // FIX 2: if the coroutine is cancelled while suspended here,
            // CancellationException propagates up through await() — that's
            // the correct structured-concurrency behaviour.
            val continueAnyway = decision.await()
            offlineDecision = null
            if (!continueAnyway) {
                // User chose to cancel downloads. Scaffold already done —
                // emit Done with nothing installed so they see the summary.
                finishWithDrivers(
                    rootPath = rootPath,
                    installedNames = emptyList(),
                    skipped = skippedAlreadyInstalled,
                    failures = emptyList(),
                )
                return
            }
            // continueAnyway == true: fall through to processEntries.
            // Per-app network errors will surface in the Done summary.
        }

        // --- Phases 3-5: resolve → download → install ---
        val result = processEntries(toProcess, rootPath)
        failedEntries = result.failedEntries

        // --- Phase 6: stage GPU drivers + emit Done ---
        finishWithDrivers(
            rootPath = rootPath,
            installedNames = result.installedNames,
            skipped = skippedAlreadyInstalled + result.cancelledNames.map { "$it (cancelled)" },
            failures = result.failures,
        )
    }

    /** Outcome of running resolve → download → install over a batch. */
    private data class ProcessResult(
        val installedNames: List<String>,
        val cancelledNames: List<String>,
        val failures: List<Pair<String, String>>,
        /** AppEntry objects that failed — for a later retry. */
        val failedEntries: List<AppEntry>,
    )

    /**
     * Runs the resolve → download → install sub-pipeline for [entries]
     * and reports the outcome. Shared by the main run and retryFailed()
     * so the DRIVER_ZIP vs APK routing lives in exactly one place.
     *
     * Does NOT scaffold folders or stage GPU drivers — callers own those.
     */
    private suspend fun processEntries(
        entries: List<AppEntry>,
        rootPath: String,
    ): ProcessResult {
        val failures = mutableListOf<Pair<String, String>>()  // (entry.name, reason)
        // Track which AppEntry each failure came from so a retry can
        // re-resolve them. resolve/download failures map back to a known
        // entry; we accumulate the still-failing entries here.
        val failedEntrySet = linkedMapOf<String, AppEntry>()  // id -> entry
        fun fail(entry: AppEntry, reason: String) {
            failures += entry.name to reason
            failedEntrySet[entry.id] = entry
        }

        // --- Phase 3: resolve URLs (concurrent, bounded to 5 in-flight) ---
        // Resolve all entries in parallel up to RESOLVE_CONCURRENCY at a time.
        // We track a thread-safe completion counter so the Resolving state
        // shows real progress even though tasks finish out of order.
        data class Resolved(val entry: AppEntry, val info: ResolveResult.Found)
        val resolved = mutableListOf<Resolved>()

        val resolveSemaphore = Semaphore(permits = RESOLVE_CONCURRENCY)
        val doneCount = AtomicInteger(0)

        emitState(State.Resolving(done = 0, total = entries.size, currentApp = ""))

        // Launch all resolves concurrently under a shared scope so failures in
        // one don't cancel others — they just record into fail(). coroutineScope
        // suspends until all async tasks complete.
        data class ResolveOutcome(val entry: AppEntry, val result: ResolveResult)
        val outcomes: List<ResolveOutcome> = coroutineScope {
            entries.map { entry ->
                async {
                    resolveSemaphore.withPermit {
                        val r = router.resolve(entry)
                        val n = doneCount.incrementAndGet()
                        emitState(
                            State.Resolving(
                                done = n,
                                total = entries.size,
                                currentApp = entry.name,
                            )
                        )
                        ResolveOutcome(entry, r)
                    }
                }
            }.awaitAll()
        }

        for ((entry, r) in outcomes) {
            when (r) {
                is ResolveResult.Found -> resolved += Resolved(entry, r)
                is ResolveResult.Failed -> fail(entry, r.reason)
                ResolveResult.Unsupported ->
                    fail(entry, "Source not supported (${entry.source})")
            }
        }
        // Source-rot self-healing: log which entries recovered via a fallback tier
        // so the Done summary can annotate them (e.g. "Dolphin (older release)").
        // The annotation is purely cosmetic — the install result is the same either way.
        if (resolved.isEmpty()) {
            return ProcessResult(emptyList(), emptyList(), failures, failedEntrySet.values.toList())
        }

        // --- Phase 4: download ---
        // Track each downloaded file with its kind so the install phase
        // can route APKs to PackageInstaller and driver zips to
        // Emulation/tools/turnip/.
        data class Downloaded(
            val entry: AppEntry,
            val file: File,
            val kind: io.github.mayusi.emutran.data.source.AssetKind,
            // Source-rot self-healing: non-null if the entry was resolved via a
            // fallback tier. Carried through to the install phase so the Done
            // summary can annotate it (e.g. "Dolphin (older release)").
            val recoveredVia: io.github.mayusi.emutran.data.source.RecoveryTier? = null,
        )
        val downloaded = mutableListOf<Downloaded>()
        for ((index, item) in resolved.withIndex()) {
            emitState(
                State.Downloading(
                    done = index,
                    total = resolved.size,
                    currentApp = item.entry.name,
                    currentBytes = 0L,
                    currentTotalBytes = item.info.sizeBytes ?: 0L,
                )
            )
            var apkFile: File? = null
            // FIX 1 (SHA-256 sidecar): if the source found a sidecar asset, fetch the hash
            // now and pass it to the downloader for post-download verification.
            // When sha256Url is non-null, a fetch failure is treated as a hard error
            // (we do NOT silently install without verification — that would defeat the point).
            val expectedSha256: String? = if (item.info.sha256Url != null) {
                val hash = router.fetchSha256Sidecar(item.info.sha256Url)
                if (hash == null) {
                    fail(item.entry, "SHA-256 sidecar fetch failed — refusing to download without integrity check")
                    continue
                }
                hash
            } else {
                null  // No sidecar published for this release; download proceeds unverified.
            }
            // Throttle: track last time we emitted a Chunk-based state update.
            // A 100 MB APK at 64 KB chunks = ~1 600 events; throttling to 200 ms cuts
            // that to ~5 updates/sec while still showing real-time progress.
            var lastEmitMs = 0L
            downloader.download(item.info.apkUrl, item.info.filename, expectedSha256).collect { p ->
                when (p) {
                    is ApkDownloader.Progress.Started ->
                        // Always emit Started immediately — this is the initial state transition.
                        emitState(
                            State.Downloading(
                                done = index, total = resolved.size,
                                currentApp = item.entry.name,
                                currentBytes = 0L,
                                currentTotalBytes = p.totalBytes,
                            )
                        )
                    is ApkDownloader.Progress.Chunk -> {
                        // Throttle: emit at most once per 200 ms, OR when the download
                        // is complete (downloaded == totalBytes) so the UI always shows 100%.
                        val now = System.currentTimeMillis()
                        val isFinal = p.totalBytes > 0L && p.downloaded >= p.totalBytes
                        if (isFinal || (now - lastEmitMs) >= 200L) {
                            lastEmitMs = now
                            emitState(
                                State.Downloading(
                                    done = index, total = resolved.size,
                                    currentApp = item.entry.name,
                                    currentBytes = p.downloaded,
                                    currentTotalBytes = p.totalBytes,
                                )
                            )
                        }
                    }
                    is ApkDownloader.Progress.Done -> apkFile = p.file
                    is ApkDownloader.Progress.Failed ->
                        fail(item.entry, p.message)
                }
            }
            apkFile?.let {
                downloaded += Downloaded(
                    entry = item.entry,
                    file = it,
                    kind = item.info.kind,
                    recoveredVia = item.info.recoveredVia,
                )
            }
        }
        if (downloaded.isEmpty()) {
            return ProcessResult(emptyList(), emptyList(), failures, failedEntrySet.values.toList())
        }

        // --- Phase 5: install ---
        // Two paths now:
        //   APK         -> InstallerRouter (Shizuku silent or system dialog)
        //   DRIVER_ZIP  -> copy into Emulation/tools/turnip/ verbatim,
        //                  no install — the user loads it inside each
        //                  emulator's Custom Driver setting.
        val isSilent = installer.currentMode() == io.github.mayusi.emutran
            .domain.install.InstallerRouter.Mode.SHIZUKU_SILENT
        // FIX 5: delegate to EmulationPaths.resolveTurnipDir instead of the
        // inline private copy — single source of truth.
        val turnipDir = resolveTurnipDir(rootPath).apply { mkdirs() }
        val installedNames = mutableListOf<String>()
        val cancelledNames = mutableListOf<String>()
        for ((index, item) in downloaded.withIndex()) {
            emitState(
                State.Installing(
                    done = index,
                    total = downloaded.size,
                    currentApp = item.entry.name,
                    silent = isSilent,
                )
            )
            when (item.kind) {
                io.github.mayusi.emutran.data.source.AssetKind.DRIVER_ZIP -> {
                    // Copy the zip into tools/turnip/ — overwrite if it
                    // already exists so re-running picks up new releases.
                    val dest = File(turnipDir, item.file.name)
                    try {
                        item.file.copyTo(dest, overwrite = true)
                        val driverLabel = if (item.recoveredVia != null) {
                            "${item.entry.name} (driver — older release)"
                        } else {
                            "${item.entry.name} (driver)"
                        }
                        installedNames += driverLabel
                    } catch (t: Throwable) {
                        fail(item.entry, t.message ?: "Copy failed")
                    }
                }
                io.github.mayusi.emutran.data.source.AssetKind.APK -> {
                    when (val r = installer.install(item.file)) {
                        InstallResult.Installed -> {
                            // Source-rot self-healing: annotate the Done summary when
                            // the install used a fallback (older) release.
                            val installedLabel = if (item.recoveredVia != null) {
                                "${item.entry.name} (older release — latest unavailable)"
                            } else {
                                item.entry.name
                            }
                            installedNames += installedLabel
                        }
                        InstallResult.Cancelled ->
                            cancelledNames += item.entry.name
                        // The OS blocked the install because the per-app "Install
                        // unknown apps" grant is missing. Surface it as a failure
                        // for this entry with an actionable reason so the user knows
                        // to enable the permission (mirrors the dashboard update path
                        // which deep-links to settings).
                        InstallResult.NeedsPermission ->
                            fail(
                                item.entry,
                                "Enable 'Install unknown apps' for EmuTran, then retry",
                            )
                        is InstallResult.Failed ->
                            fail(item.entry, r.message)
                    }
                }
            }
        }

        return ProcessResult(
            installedNames = installedNames,
            cancelledNames = cancelledNames,
            failures = failures,
            failedEntries = failedEntrySet.values.toList(),
        )
    }

    /**
     * Final phase shared by every exit path: detect GPU, stage drivers
     * if Adreno, then emit [State.Done]. Called from all early returns
     * too (no installs needed, no resolves, no downloads succeeded) so
     * drivers always get a chance to run.
     *
     * Resolves the turnip path carefully to avoid the
     * /sdcard/Emulation/Emulation/tools/ double-suffix bug — if the
     * user's chosen root already ends in /Emulation, we don't append
     * another segment.
     */
    private suspend fun finishWithDrivers(
        rootPath: String,
        installedNames: List<String>,
        skipped: List<String>,
        failures: List<Pair<String, String>>,
    ) {
        // Hard short-circuit when the user didn't opt in. Driver staging
        // is niche (only matters for Switch/PS Vita emulator users) and
        // shouldn't auto-run for everyone.
        val wantDrivers = setupOptions.stageGpuDrivers.first()
        if (!wantDrivers) {
            emitState(
                State.Done(
                    installed = installedNames,
                    skipped = skipped,
                    failed = failures,
                    drivers = DriverSummary.Skipped("Not enabled"),
                )
            )
            return
        }

        val gpu = gpuDetector.snapshot()
        var driverSummary: DriverSummary = DriverSummary.Skipped("GPU not Adreno")
        when (val discover = driverStager.discover(gpu)) {
            is DriverStager.DiscoverResult.NotApplicable ->
                driverSummary = DriverSummary.Skipped(discover.reason)
            is DriverStager.DiscoverResult.Failed ->
                driverSummary = DriverSummary.Failed(discover.reason)
            is DriverStager.DiscoverResult.Found -> {
                emitState(
                    State.StagingDrivers(
                        releaseTag = discover.releaseTag,
                        assetCount = discover.assets.size,
                    )
                )
                // FIX 5: use EmulationPaths.resolveTurnipDir.
                val turnipDir = resolveTurnipDir(rootPath)
                val stage = driverStager.stage(discover.assets, turnipDir)
                driverSummary = when (stage) {
                    is DriverStager.StageResult.Done ->
                        DriverSummary.Staged(
                            releaseTag = discover.releaseTag,
                            files = stage.saved,
                            failedFiles = stage.failed.map { it.first },
                        )
                    is DriverStager.StageResult.Failed ->
                        DriverSummary.Failed(stage.reason)
                }
            }
        }

        emitState(
            State.Done(
                installed = installedNames,
                skipped = skipped,
                failed = failures,
                drivers = driverSummary,
            )
        )
    }

    /**
     * Registers this ViewModel's state with [SetupServiceBridge] and starts
     * the service. The service watches the bridge flow and stops itself when
     * it sees a terminal state (Done, Failed, Cancelled).
     *
     * FIX 4: no longer sets a static var on the service companion object.
     * The bridge is a @Singleton shared between this VM and the service via
     * Hilt injection.
     */
    private fun startSetupService() {
        // Sync the bridge before starting the service so the service sees
        // the current state immediately on onStartCommand.
        bridge.mutable.value = _state.value
        context.startForegroundService(
            Intent(context, SetupForegroundService::class.java)
        )
    }

    sealed interface State {
        data object Idle : State

        data class Scaffolding(val done: Int, val total: Int, val label: String) : State

        /**
         * Emitted after folder scaffolding completes but before the
         * resolve/download phase, when the device has no network connection.
         * The pipeline is suspended here waiting for [confirmOffline] to be
         * called by the UI. No payload — the dialog text is static.
         */
        data object OfflineWarning : State

        data class Resolving(
            val done: Int,
            val total: Int,
            val currentApp: String,
        ) : State

        data class Downloading(
            val done: Int,
            val total: Int,
            val currentApp: String,
            val currentBytes: Long,
            val currentTotalBytes: Long,
        ) : State

        data class Installing(
            val done: Int,
            val total: Int,
            val currentApp: String,
            /** True when the loop is going through Shizuku (no dialogs). */
            val silent: Boolean,
        ) : State

        data class StagingDrivers(
            val releaseTag: String,
            val assetCount: Int,
        ) : State

        data class Done(
            val installed: List<String>,
            val skipped: List<String>,
            val failed: List<Pair<String, String>>,
            val drivers: DriverSummary = DriverSummary.Skipped("Not run"),
        ) : State

        data class Failed(
            val message: String,
            val partial: List<String>,
        ) : State

        /**
         * FIX 1: emitted by [cancelAndReset] when the user explicitly cancels
         * the setup run. This is a proper terminal state so [SetupForegroundService]
         * calls [android.app.Service.stopSelf] and dismisses the notification
         * immediately, instead of leaving a stale "Setting up…" entry in the shade.
         *
         * [ProgressScreen] does not need to render this state — the cancel dialog
         * calls [cancelAndReset] and then immediately invokes [onGoToDashboard], so
         * the composable is already navigating away when Cancelled is emitted.
         */
        data object Cancelled : State
    }

    companion object {
        /** Max concurrent router.resolve() calls in Phase 3. */
        private const val RESOLVE_CONCURRENCY = 5
    }

    /** Outcome of the GPU driver staging step. */
    sealed interface DriverSummary {
        data class Staged(
            val releaseTag: String,
            val files: List<String>,
            val failedFiles: List<String>,
        ) : DriverSummary
        data class Skipped(val reason: String) : DriverSummary
        data class Failed(val reason: String) : DriverSummary
    }
}
