package io.github.mayusi.emutran.ui.bios

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.mayusi.emutran.data.storage.StorageRootStore
import io.github.mayusi.emutran.domain.health.BiosFileResult
import io.github.mayusi.emutran.domain.health.BiosFileStatus
import io.github.mayusi.emutran.domain.health.BiosValidator
import io.github.mayusi.emutran.domain.scaffold.resolveEmulationRoot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

// ─────────────────────────────────────────────────────────────────────────────
// UI model types
// ─────────────────────────────────────────────────────────────────────────────

/**
 * The user-facing verdict for a single BIOS file entry.
 *
 * - [VERIFIED]  — file present and md5 matches a known-good reference hash.
 * - [PRESENT]   — file exists but no reference hash is available to verify it.
 * - [WRONG_HASH]— file present but md5 does NOT match the known reference hash
 *                 (corrupt, wrong region, or a different dump).
 * - [MISSING]   — file is not found in the system's bios folder.
 */
enum class BiosFileVerdict { VERIFIED, PRESENT, WRONG_HASH, MISSING }

/**
 * Display model for one expected BIOS file within a system card.
 *
 * @param filename  Expected filename (e.g. "scph1001.bin").
 * @param verdict   Condensed user-facing verdict derived from [BiosFileStatus].
 * @param note      Optional parenthetical from the spec (e.g. "USA alt").
 *                  Empty string when the raw filename had no note.
 */
data class BiosFileEntry(
    val filename: String,
    val verdict: BiosFileVerdict,
    val note: String = "",
)

/**
 * Display model for one emulation system's BIOS section.
 *
 * @param systemKey      Internal folder name (e.g. "ps1").
 * @param displayName    Human-readable system name (e.g. "PlayStation 1").
 * @param usedBy         Emulator name(s) that consume this BIOS.
 * @param folderPath     Absolute path of the bios/<system> folder on disk so
 *                       the user knows exactly where to drop files.
 * @param folderExists   Whether the system's bios folder is present on disk.
 * @param files          Per-file breakdown in spec order. Empty when no
 *                       cleanable filenames exist for this system (prose-only
 *                       systems like psp, psvita, wiiu, retroarch).
 * @param isProseSys     True when the system's spec contains only prose / install
 *                       instructions rather than concrete filenames to validate.
 *                       The card shows a guidance note instead of a file checklist.
 */
data class BiosSystemEntry(
    val systemKey: String,
    val displayName: String,
    val usedBy: String,
    val folderPath: String,
    val folderExists: Boolean,
    val files: List<BiosFileEntry>,
    val isProseSys: Boolean,
) {
    /** Number of files that are either VERIFIED or PRESENT (usable). */
    val readyCount: Int get() = files.count { it.verdict == BiosFileVerdict.VERIFIED || it.verdict == BiosFileVerdict.PRESENT }

    /** True when every expected concrete file is either VERIFIED or PRESENT. */
    val isReady: Boolean get() = !isProseSys && files.isNotEmpty() && readyCount == files.size
}

/** UI state for [BiosHelperViewModel]. */
sealed interface BiosHelperUiState {
    /** Scan in progress — show a loading spinner. */
    data object Loading : BiosHelperUiState

    /**
     * No storage root has been configured yet — the user needs to complete
     * setup before BIOS paths can be resolved.
     */
    data object NoRoot : BiosHelperUiState

    /**
     * Scan complete.
     *
     * @param systems      Per-system entries in spec order.
     * @param readySystems Count of systems with all expected files present/verified.
     * @param totalSystems Total number of systems with concrete filename expectations.
     */
    data class Loaded(
        val systems: List<BiosSystemEntry>,
        val readySystems: Int,
        val totalSystems: Int,
    ) : BiosHelperUiState
}

// ─────────────────────────────────────────────────────────────────────────────
// ViewModel
// ─────────────────────────────────────────────────────────────────────────────

/**
 * ViewModel for the BIOS Helper screen.
 *
 * Delegates the read-only file scan to [BiosValidator] and maps the raw
 * [io.github.mayusi.emutran.domain.health.BiosValidationResult] into
 * [BiosHelperUiState] which the screen renders.
 *
 * All heavy work (file scanning, md5 hashing) runs on Dispatchers.IO inside
 * [BiosValidator]. This class only maps results on the calling coroutine and
 * emits into the StateFlow on the main thread via viewModelScope.
 *
 * READ-ONLY: this ViewModel never writes, deletes, or downloads any files.
 */
@HiltViewModel
class BiosHelperViewModel @Inject constructor(
    private val biosValidator: BiosValidator,
    private val storageRoot: StorageRootStore,
) : ViewModel() {

    private val _state = MutableStateFlow<BiosHelperUiState>(BiosHelperUiState.Loading)

    /** Observed by [BiosHelperScreen] to drive the UI. */
    val state: StateFlow<BiosHelperUiState> = _state.asStateFlow()

    init {
        // Kick off the initial scan as soon as the ViewModel is created.
        runScan()
    }

    /**
     * Re-runs the BIOS scan, resetting to [BiosHelperUiState.Loading] first so
     * the user sees the spinner again. Safe to call multiple times.
     */
    fun rescan() {
        runScan()
    }

    private fun runScan() {
        viewModelScope.launch {
            _state.value = BiosHelperUiState.Loading

            // Resolve the root here so we can build absolute folder paths for display.
            val rootPath = storageRoot.rootPath.first()
            if (rootPath == null) {
                _state.value = BiosHelperUiState.NoRoot
                return@launch
            }

            val validationResult = biosValidator.validate(rootPath)
            if (!validationResult.rootConfigured) {
                _state.value = BiosHelperUiState.NoRoot
                return@launch
            }

            val emulationRoot = resolveEmulationRoot(rootPath)
            val biosRoot = "${emulationRoot.absolutePath}/bios"

            val entries = validationResult.systems.map { sysStatus ->
                val meta = SYSTEM_META[sysStatus.system]
                    ?: SystemMeta(displayName = sysStatus.system, usedBy = "Unknown")

                val isProse = sysStatus.expectedCount == 0

                val fileEntries = sysStatus.files.map { fileResult ->
                    BiosFileEntry(
                        filename = fileResult.filename,
                        verdict = fileResult.status.toVerdict(),
                        note = RAW_NOTES[sysStatus.system]?.get(fileResult.filename) ?: "",
                    )
                }

                BiosSystemEntry(
                    systemKey = sysStatus.system,
                    displayName = meta.displayName,
                    usedBy = meta.usedBy,
                    folderPath = "$biosRoot/${sysStatus.system}",
                    folderExists = sysStatus.folderExists,
                    files = fileEntries,
                    isProseSys = isProse,
                )
            }

            // "Ready" = every expected concrete file present/verified.
            // Prose-only systems (psp, psvita, wiiu, retroarch) are excluded
            // from the summary counters since there's nothing the validator
            // can confirm for them.
            val concreteSystems = entries.filter { !it.isProseSys }
            val readySystems = concreteSystems.count { it.isReady }

            _state.value = BiosHelperUiState.Loaded(
                systems = entries,
                readySystems = readySystems,
                totalSystems = concreteSystems.size,
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Static metadata — mirrors FolderSpec.biosSpecs which is private.
    // The display name and usedBy text are a compile-time copy of that source
    // of truth. If FolderSpec adds a new system this map will show the key
    // as the display name and "Unknown" as the emulator — a safe degradation.
    // ─────────────────────────────────────────────────────────────────────────

    private data class SystemMeta(val displayName: String, val usedBy: String)

    companion object {
        private val SYSTEM_META: Map<String, SystemMeta> = mapOf(
            "ps1"       to SystemMeta("PlayStation 1",          "DuckStation"),
            "ps2"       to SystemMeta("PlayStation 2",          "NetherSX2 / ARMSX2"),
            "dc"        to SystemMeta("Dreamcast",              "Flycast"),
            "ds"        to SystemMeta("Nintendo DS",            "MelonDS / MelonDualDS"),
            "3ds"       to SystemMeta("Nintendo 3DS",           "Azahar / Citra MMJ"),
            "gba"       to SystemMeta("Game Boy Advance",       "RetroArch (mGBA / VBA-M)"),
            "saturn"    to SystemMeta("Sega Saturn",            "RetroArch (Beetle Saturn / YabaSanshiro)"),
            "switch"    to SystemMeta("Nintendo Switch",        "Eden / Citron"),
            "psp"       to SystemMeta("PlayStation Portable",   "PPSSPP"),
            "psvita"    to SystemMeta("PlayStation Vita",       "Vita3K"),
            "wiiu"      to SystemMeta("Wii U",                  "Cemu"),
            "retroarch" to SystemMeta("RetroArch system files", "RetroArch (all cores)"),
        )

        /**
         * Parenthetical notes for individual files, keyed by system → filename.
         * Extracted from the raw entries in FolderSpec so they can be shown
         * alongside the filename in the UI (e.g. "USA", "PAL").
         *
         * Only entries with a note are listed; absent = no note to show.
         */
        internal val RAW_NOTES: Map<String, Map<String, String>> = mapOf(
            "ps1" to mapOf(
                "scph1001.bin" to "USA",
                "scph5501.bin" to "USA alt",
                "scph7001.bin" to "USA",
                "scph1002.bin" to "PAL",
            ),
            "ps2" to mapOf(
                "SCPH-70012.bin" to "USA",
                "SCPH-70004.bin" to "Europe",
                "SCPH-70000.bin" to "Japan",
            ),
            "ds" to mapOf(
                "firmware.bin" to "optional DSi",
            ),
            "gba" to mapOf(
                "gba_bios.bin" to "optional — most cores work without it",
            ),
            "3ds" to mapOf(
                "aes_keys.txt" to "required for encrypted content",
            ),
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Extension — maps the domain verdict to the UI verdict
// ─────────────────────────────────────────────────────────────────────────────

/** Maps a domain [BiosFileStatus] to the richer [BiosFileVerdict] used in the UI. */
internal fun BiosFileStatus.toVerdict(): BiosFileVerdict = when (this) {
    BiosFileStatus.OK         -> BiosFileVerdict.VERIFIED
    BiosFileStatus.PRESENT    -> BiosFileVerdict.PRESENT
    BiosFileStatus.WRONG_HASH -> BiosFileVerdict.WRONG_HASH
    BiosFileStatus.MISSING    -> BiosFileVerdict.MISSING
}

/** Convenience: build a [BiosFileEntry] directly from a [BiosFileResult]. */
internal fun BiosFileResult.toEntry(note: String = ""): BiosFileEntry =
    BiosFileEntry(filename = filename, verdict = status.toVerdict(), note = note)
