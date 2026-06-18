package io.github.mayusi.emutran.ui.romsort

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.mayusi.emutran.data.storage.AllFilesAccess
import io.github.mayusi.emutran.data.storage.StorageRootStore
import io.github.mayusi.emutran.data.storage.StorageVolumes
import io.github.mayusi.emutran.domain.roms.RomClassifier
import io.github.mayusi.emutran.domain.roms.RomMover
import io.github.mayusi.emutran.domain.roms.RomScanner
import io.github.mayusi.emutran.domain.scaffold.resolveEmulationRoot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * ViewModel for the ROM Sort screen.
 *
 * Lifecycle:
 *   NeedsPermission → (permission granted) → Idle → Scanning → Results → Moving → Done
 *
 * The Results state groups found ROMs into:
 *   - [ConfidentGroup]: one group per system containing KnownSystem files.
 *   - [NeedsDecisionGroup]: all Ambiguous + Unknown files where the user
 *     must pick a system before moving.
 *
 * Move actions:
 *   - [moveOne]: move a single [RomEntry] to its resolved target.
 *   - [moveAllConfident]: batch-move all KnownSystem entries.
 *   - [assignSystem]: assign a system dir to an Ambiguous/Unknown entry
 *     (converts it to a moveable entry; does NOT auto-move).
 *   - [moveAssigned]: move a previously-assigned entry.
 *   - [skipEntry]: mark an entry as skipped (removed from pending lists).
 */
@HiltViewModel
class RomSortViewModel @Inject constructor(
    private val allFilesAccess: AllFilesAccess,
    private val storageVolumes: StorageVolumes,
    private val storageRootStore: StorageRootStore,
    private val romScanner: RomScanner,
    private val romMover: RomMover,
) : ViewModel() {

    // ── UI state machine ──────────────────────────────────────────────────────

    /** Top-level screen state. */
    sealed interface ScreenState {
        /** All-files access is not granted. Show explain + Settings button. */
        data object NeedsPermission : ScreenState

        /** Permission granted; no scan has been run yet. */
        data object Idle : ScreenState

        /** Scan is in progress. */
        data object Scanning : ScreenState

        /** Scan complete. Show grouped results. */
        data class Results(
            val confidentGroups: List<ConfidentGroup>,
            val needsDecisionEntries: List<RomEntry>,
            val totalFound: Int,
        ) : ScreenState

        /** One or more moves are in progress. */
        data class Moving(val doneCount: Int, val totalCount: Int) : ScreenState

        /** All requested moves finished. Show summary. */
        data class Done(
            val movedCount: Int,
            val failedCount: Int,
            val skippedCount: Int,
            val failures: List<String>,  // human-readable failure reasons
        ) : ScreenState

        /** Scan or move returned an error (storage root not configured, etc.). */
        data class Error(val message: String) : ScreenState
    }

    /**
     * A group of ROMs that are confidently classified for one system.
     *
     * @param systemDir  Folder name under Emulation/roms/ (e.g. "gba").
     * @param targetDir  The resolved [File] for Emulation/roms/<systemDir>/.
     * @param entries    The individual ROM entries in this group.
     */
    data class ConfidentGroup(
        val systemDir: String,
        val targetDir: File,
        val entries: List<RomEntry>,
    )

    /**
     * A single ROM file being tracked through the sort process.
     *
     * @param found       The underlying scanner result.
     * @param assignedDir User-assigned target dir (non-null after [assignSystem]).
     * @param moveResult  The outcome once a move has been attempted.
     */
    data class RomEntry(
        val found: RomScanner.FoundRom,
        val assignedDir: File? = null,
        val moveResult: RomMover.MoveResult? = null,
    ) {
        /** True when this entry has been moved or explicitly skipped. */
        val isDone: Boolean get() = moveResult != null
    }

    private val _state = MutableStateFlow<ScreenState>(ScreenState.Idle)
    val state: StateFlow<ScreenState> = _state.asStateFlow()

    // Internal mutable list of all found entries — mutated during move ops.
    private var allEntries: MutableList<RomEntry> = mutableListOf()

    init {
        checkPermission()
    }

    // ── Permission ────────────────────────────────────────────────────────────

    fun checkPermission() {
        _state.value = if (allFilesAccess.isGranted()) ScreenState.Idle else ScreenState.NeedsPermission
    }

    // ── Scan ──────────────────────────────────────────────────────────────────

    /**
     * Starts a new scan. Moves the state to [Scanning] immediately, then
     * updates to [Results] (or [Error]) when complete.
     */
    fun scan() {
        if (!allFilesAccess.isGranted()) {
            _state.value = ScreenState.NeedsPermission
            return
        }

        viewModelScope.launch {
            _state.value = ScreenState.Scanning

            // Resolve storage root.
            val rootPath = storageRootStore.rootPath.first()
                ?: storageRootStore.defaultPath
            val emulationRoot = resolveEmulationRoot(rootPath)

            // Get volume roots.
            val volumeRoots = storageVolumes.list().map { File(it.path) }
            if (volumeRoots.isEmpty()) {
                _state.value = ScreenState.Error("No storage volumes found.")
                return@launch
            }

            // Run the scan.
            val found = runCatching {
                romScanner.scan(volumeRoots, emulationRoot)
            }.getOrElse { e ->
                _state.value = ScreenState.Error("Scan failed: ${e.message ?: "unknown error"}")
                return@launch
            }

            allEntries = found.map { RomEntry(it) }.toMutableList()

            _state.value = buildResultsState(emulationRoot)
        }
    }

    // ── Move operations ───────────────────────────────────────────────────────

    /**
     * Moves a single entry.
     * The entry's [RomEntry.found] must have a non-null [suggestedTargetDir]
     * OR the entry must have an [assignedDir] set.
     */
    fun moveOne(entry: RomEntry) {
        val targetDir = entry.assignedDir ?: entry.found.suggestedTargetDir ?: return
        viewModelScope.launch {
            performMoves(listOf(entry to targetDir))
        }
    }

    /**
     * Moves all entries whose classification is [RomClassifier.Classification.KnownSystem]
     * and that have not already been moved or skipped.
     */
    fun moveAllConfident() {
        val toMove = allEntries.filter { entry ->
            !entry.isDone &&
                entry.found.classification is RomClassifier.Classification.KnownSystem &&
                entry.found.suggestedTargetDir != null
        }.map { it to it.found.suggestedTargetDir!! }

        if (toMove.isEmpty()) return

        viewModelScope.launch {
            performMoves(toMove)
        }
    }

    /**
     * Assigns a system dir to an ambiguous/unknown entry, making it moveable.
     * Does NOT auto-move — the user confirms by calling [moveAssigned].
     *
     * @param entry     The entry to update.
     * @param systemDir The chosen system folder name (e.g. "ps1", "gc").
     * @param emulationRoot The resolved Emulation/ directory for building the target path.
     */
    fun assignSystem(entry: RomEntry, systemDir: String, emulationRoot: File) {
        val targetDir = File(File(emulationRoot, "roms"), systemDir)
        val idx = allEntries.indexOfFirst { it.found.file == entry.found.file }
        if (idx < 0) return
        allEntries[idx] = allEntries[idx].copy(assignedDir = targetDir)
        _state.value = buildResultsState(emulationRoot)
    }

    /**
     * Moves an entry that previously had a system assigned via [assignSystem].
     */
    fun moveAssigned(entry: RomEntry) {
        val targetDir = entry.assignedDir ?: return
        viewModelScope.launch {
            performMoves(listOf(entry to targetDir))
        }
    }

    /**
     * Marks an entry as explicitly skipped (sets a [RomMover.MoveResult.Skipped]
     * result on it so it no longer appears in the pending list).
     */
    fun skipEntry(entry: RomEntry) {
        val idx = allEntries.indexOfFirst { it.found.file == entry.found.file }
        if (idx < 0) return
        allEntries[idx] = allEntries[idx].copy(
            moveResult = RomMover.MoveResult.Skipped("Skipped by user.")
        )
        // Rebuild results state to reflect the skip — need emulationRoot for that.
        viewModelScope.launch {
            val rootPath = storageRootStore.rootPath.first() ?: storageRootStore.defaultPath
            _state.value = buildResultsState(resolveEmulationRoot(rootPath))
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Executes a batch of moves, updating state as each move completes.
     * Transitions to [Done] at the end.
     */
    private suspend fun performMoves(moves: List<Pair<RomEntry, File>>) {
        var doneCount = 0
        _state.value = ScreenState.Moving(doneCount, moves.size)

        val failures = mutableListOf<String>()

        for ((entry, targetDir) in moves) {
            val result = romMover.move(entry.found.file, targetDir)

            // Update the entry's result in our list.
            val idx = allEntries.indexOfFirst { it.found.file == entry.found.file }
            if (idx >= 0) {
                allEntries[idx] = allEntries[idx].copy(moveResult = result)
            }

            if (result is RomMover.MoveResult.Failed) {
                failures.add(result.reason)
            }

            doneCount++
            _state.value = ScreenState.Moving(doneCount, moves.size)
        }

        // Build summary.
        val movedCount   = allEntries.count { it.moveResult is RomMover.MoveResult.Moved }
        val skippedCount = allEntries.count {
            it.moveResult is RomMover.MoveResult.Skipped &&
                (it.moveResult as RomMover.MoveResult.Skipped).reason == "Skipped by user."
        }
        val failedCount  = allEntries.count { it.moveResult is RomMover.MoveResult.Failed }

        _state.value = ScreenState.Done(
            movedCount   = movedCount,
            failedCount  = failedCount,
            skippedCount = skippedCount,
            failures     = failures,
        )
    }

    /**
     * Rebuilds [ScreenState.Results] from the current [allEntries] list,
     * grouping confident entries by system and collecting ambiguous/unknown
     * into the "needs decision" bucket.
     */
    private fun buildResultsState(emulationRoot: File): ScreenState.Results {
        // Only include entries not yet done.
        val pending = allEntries.filter { !it.isDone }

        val confidentBySystem = mutableMapOf<String, MutableList<RomEntry>>()
        val needsDecision = mutableListOf<RomEntry>()

        for (entry in pending) {
            when (val cls = entry.found.classification) {
                is RomClassifier.Classification.KnownSystem -> {
                    confidentBySystem.getOrPut(cls.dir) { mutableListOf() }.add(entry)
                }
                else -> needsDecision.add(entry)
            }
        }

        val confidentGroups = confidentBySystem.entries
            .sortedBy { it.key }
            .map { (systemDir, entries) ->
                ConfidentGroup(
                    systemDir = systemDir,
                    targetDir = File(File(emulationRoot, "roms"), systemDir),
                    entries   = entries,
                )
            }

        return ScreenState.Results(
            confidentGroups      = confidentGroups,
            needsDecisionEntries = needsDecision,
            totalFound           = allEntries.size,
        )
    }

    /**
     * Resets the screen back to [Idle] so the user can start over.
     * Useful after [Done] or [Error].
     */
    fun reset() {
        allEntries = mutableListOf()
        _state.value = ScreenState.Idle
    }

    // ── Screen-layer helpers ──────────────────────────────────────────────────

    /**
     * Exposes [AllFilesAccess] to the Composable layer so the permission-gate
     * section can fire [AllFilesAccess.requestIntent] without needing a direct
     * injection in the screen. This avoids passing Context-dependent objects
     * deep into Composable parameters.
     */
    fun accessHelper(): AllFilesAccess = allFilesAccess

    /**
     * Used by the ambiguous-entry system picker in the Composable.
     *
     * Resolves the current emulation root and calls [assignSystem] on [entry],
     * then invokes [onComplete] with the resolved emulation root [File] so the
     * Composable can, e.g., update any local state if needed.
     *
     * @param entry       The ROM entry being assigned.
     * @param systemDir   The chosen system folder name (e.g. "ps1").
     * @param onComplete  Called after the assignment is persisted, with the
     *   resolved emulation root (may be useful to the caller for display).
     */
    fun assignSystemFromScreen(
        entry: RomEntry,
        systemDir: String,
        onComplete: (emulationRoot: File) -> Unit = {},
    ) {
        viewModelScope.launch {
            val rootPath = storageRootStore.rootPath.first() ?: storageRootStore.defaultPath
            val emulationRoot = resolveEmulationRoot(rootPath)
            assignSystem(entry, systemDir, emulationRoot)
            onComplete(emulationRoot)
        }
    }
}
