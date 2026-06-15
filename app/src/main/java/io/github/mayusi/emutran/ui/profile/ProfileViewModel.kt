package io.github.mayusi.emutran.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.mayusi.emutran.data.manifest.ObtainiumPackParser
import io.github.mayusi.emutran.data.profile.ImportResult
import io.github.mayusi.emutran.data.profile.ProfileRepository
import io.github.mayusi.emutran.data.selection.SelectedAppsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * ViewModel for the Setup Profile backup / restore screen.
 *
 * Drives two independent flows, each with its own one-shot result state:
 *
 *  - Export: serializes the current setup ([ProfileRepository.export]) and
 *    writes the JSON to the default on-device path
 *    (<storageRoot>/Emulation/EmuTran/profile.json). The repository itself
 *    does *not* touch disk, so the write happens here. The resulting path is
 *    surfaced so the screen can show "saved to …".
 *
 *  - Import: the screen runs an ACTION_OPEN_DOCUMENT picker, reads the raw
 *    JSON bytes, and hands the string to [importProfile]. The repository
 *    validates + applies the safe device-local values and never throws on bad
 *    JSON — a malformed file comes back as [ImportResult.Failed], surfaced
 *    here as [ImportUiState.Failed].
 *
 * The imported storage root is NOT adopted automatically: [import] returns a
 * *proposed* root the user must confirm. When that proposal is valid and
 * differs from the current device root, [ImportUiState.Done.proposedRoot] is
 * non-null and the screen offers "Use imported path" — which calls
 * [applyProposedRoot] → [ProfileRepository.applyStorageRoot].
 *
 * All file/serialization work runs off the main thread (repository uses
 * Dispatchers.IO; the export write is wrapped in withContext(Dispatchers.IO)).
 */
@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val repository: ProfileRepository,
    private val manifestParser: ObtainiumPackParser,
    private val selectedApps: SelectedAppsStore,
) : ViewModel() {

    /** One-shot state for the Export action. */
    sealed interface ExportUiState {
        /** No export attempted yet (or it was consumed/reset). */
        data object Idle : ExportUiState

        /** Export in progress — disable the button, show a spinner. */
        data object Working : ExportUiState

        /** Export wrote the profile JSON to [path]. */
        data class Done(val path: String) : ExportUiState

        /** Export failed to write (e.g. I/O error). */
        data class Failed(val reason: String) : ExportUiState
    }

    /** One-shot state for the Import action. */
    sealed interface ImportUiState {
        /** No import attempted yet (or it was consumed/reset). */
        data object Idle : ImportUiState

        /** Import in progress (reading + validating). */
        data object Working : ImportUiState

        /**
         * Import succeeded; the safe device-local values have already been
         * applied to the stores. The storage root is NOT yet applied — see
         * [proposedRoot].
         *
         * @property appliedCount how many picked emulators were applied.
         * @property droppedNames display names (or ids when a name can't be
         *   resolved) of emulators that couldn't be restored.
         * @property proposedRoot a validated-but-unsaved storage root for the
         *   user to confirm, or null when there's nothing to confirm (no usable
         *   root, invalid path, or it matches the current root).
         */
        data class Done(
            val appliedCount: Int,
            val droppedNames: List<String>,
            val proposedRoot: ProposedRootUi?,
        ) : ImportUiState

        /** Import failed — nothing was applied. */
        data class Failed(val reason: String) : ImportUiState
    }

    /**
     * UI-facing storage-root proposal. Mirrors the repository's ProposedRoot
     * but only carries what the confirmation block needs.
     *
     * @property path the canonical path that would be saved if accepted.
     * @property isValid true when the path is safe to adopt on this device; when
     *   false the UI must NOT offer "Use imported path" and should explain the
     *   imported path can't be used here.
     */
    data class ProposedRootUi(
        val path: String,
        val isValid: Boolean,
    )

    private val _exportState = MutableStateFlow<ExportUiState>(ExportUiState.Idle)
    val exportState: StateFlow<ExportUiState> = _exportState.asStateFlow()

    private val _importState = MutableStateFlow<ImportUiState>(ImportUiState.Idle)
    val importState: StateFlow<ImportUiState> = _importState.asStateFlow()

    /**
     * True when the current picked-emulator selection is empty. The screen uses
     * this to guard the Export action (nothing meaningful to back up yet).
     */
    private val _hasPicks = MutableStateFlow(true)
    val hasPicks: StateFlow<Boolean> = _hasPicks.asStateFlow()

    init {
        viewModelScope.launch {
            selectedApps.pickedIds.collect { ids -> _hasPicks.value = ids.isNotEmpty() }
        }
    }

    /**
     * Serialize the current setup and write it to the default profile path.
     *
     * [ProfileRepository.export] only produces the JSON + the target path; the
     * actual write is performed here so the repository stays free of disk side
     * effects.
     */
    fun exportProfile() {
        if (_exportState.value is ExportUiState.Working) return
        _exportState.value = ExportUiState.Working
        viewModelScope.launch {
            val result = runCatching {
                val export = repository.export()
                withContext(Dispatchers.IO) {
                    val target = File(export.path)
                    target.parentFile?.mkdirs()
                    target.writeText(export.json)
                }
                export.path
            }
            _exportState.value = result.fold(
                onSuccess = { ExportUiState.Done(it) },
                onFailure = { ExportUiState.Failed(it.message ?: "Could not write profile file") },
            )
        }
    }

    /**
     * Apply an imported profile from raw JSON [json] (already read from the
     * user-picked document by the screen).
     *
     * Delegates validation + persistence of the safe fields to
     * [ProfileRepository.import], which never throws — malformed JSON returns
     * [ImportResult.Failed]. The dropped ids are resolved to human-readable
     * names where possible; the proposed storage root is surfaced for the user
     * to confirm only when it's valid and differs from the current root.
     */
    fun importProfile(json: String) {
        // Note: beginImport() is always called before importProfile() by the screen,
        // so importState is already Working here. Do NOT guard on Working — that guard
        // would silently swallow every import invocation. Re-entry is prevented at the
        // screen level (the launcher is only launched when !importing).
        _importState.value = ImportUiState.Working
        viewModelScope.launch {
            _importState.value = when (val res = repository.import(json)) {
                is ImportResult.Success -> {
                    val droppedNames = resolveDroppedNames(res.droppedIds)
                    val proposed = res.proposedStorageRoot
                        ?.takeIf { it.differsFromCurrent }
                        ?.let { ProposedRootUi(path = it.path, isValid = it.isValid) }
                    ImportUiState.Done(
                        appliedCount = res.appliedCount,
                        droppedNames = droppedNames,
                        proposedRoot = proposed,
                    )
                }
                is ImportResult.Failed -> ImportUiState.Failed(friendlyImportError(res.reason))
            }
        }
    }

    /**
     * Persist a storage root the user explicitly accepted from the prior
     * import's proposal. Re-validation happens inside the repository; a false
     * return means the path was rejected and nothing was written.
     */
    fun applyProposedRoot(path: String) {
        viewModelScope.launch {
            val applied = repository.applyStorageRoot(path)
            // Whatever the outcome, the confirmation has been resolved — drop the
            // proposal from the success state so the prompt doesn't linger.
            val current = _importState.value
            if (current is ImportUiState.Done) {
                _importState.value = current.copy(proposedRoot = null)
            }
            if (!applied) {
                // Defensive: the path failed re-validation. Surface it rather
                // than silently doing nothing.
                _importState.value = ImportUiState.Failed(
                    "That storage path isn't valid on this device — keeping the current one.",
                )
            }
        }
    }

    /**
     * Dismiss the storage-root proposal without applying it ("Keep current").
     * Leaves every already-applied field intact.
     */
    fun keepCurrentRoot() {
        val current = _importState.value
        if (current is ImportUiState.Done) {
            _importState.value = current.copy(proposedRoot = null)
        }
    }

    /** Surface a read failure from the file picker (e.g. unreadable document). */
    fun reportImportReadError(reason: String) {
        _importState.value = ImportUiState.Failed(reason)
    }

    /** Mark an import as in-flight the moment the picker returns a uri. */
    fun beginImport() {
        if (_importState.value is ImportUiState.Working) return
        _importState.value = ImportUiState.Working
    }

    /** Reset the export state back to Idle (after the snackbar is shown). */
    fun consumeExportState() {
        _exportState.value = ExportUiState.Idle
    }

    /** Reset the import state back to Idle. */
    fun consumeImportState() {
        _importState.value = ImportUiState.Idle
    }

    /**
     * Resolve dropped emulator ids to display names using the UNION of both
     * bundled manifest variants (mirrors the repository's validation union).
     * Dropped ids are by definition absent from the bundled manifests, so the
     * id itself is the expected fallback — but if a future remote manifest knows
     * the name we'll show it. Never throws.
     */
    private suspend fun resolveDroppedNames(droppedIds: List<String>): List<String> {
        if (droppedIds.isEmpty()) return emptyList()
        val nameById = runCatching {
            val standard = runCatching { manifestParser.loadBundledOnly() }.getOrDefault(emptyList())
            val dual = runCatching { manifestParser.loadDualScreen() }.getOrDefault(emptyList())
            (standard + dual).associate { it.id to it.name }
        }.getOrDefault(emptyMap())
        return droppedIds.map { id -> nameById[id] ?: id }
    }

    /**
     * Map a raw failure reason (often a kotlinx.serialization exception message)
     * to friendly copy. Anything that looks like a parse/serialization error is
     * rewritten; an already-friendly message (e.g. the newer-schema rejection)
     * is passed through.
     */
    private fun friendlyImportError(reason: String): String {
        val r = reason.lowercase()
        val looksLikeSerialization = r.isBlank() ||
            "serializ" in r ||
            "json" in r ||
            "decod" in r ||
            "unexpected" in r ||
            "expected" in r ||
            "malformed" in r ||
            "illegal" in r ||
            "missing field" in r ||
            "eof" in r
        return if (looksLikeSerialization) {
            "That file isn't a valid EmuTran profile — pick the profile.json you exported."
        } else {
            reason
        }
    }

    companion object {
        /**
         * Hard cap on the size (bytes) the screen will read from a picked
         * document before treating it as "too large to be a profile". A real
         * profile is a few hundred bytes; 256 KiB is generous headroom.
         */
        const val MAX_PROFILE_BYTES: Long = 256L * 1024L
    }
}
