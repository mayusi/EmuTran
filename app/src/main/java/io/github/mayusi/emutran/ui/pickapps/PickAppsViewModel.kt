package io.github.mayusi.emutran.ui.pickapps

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.mayusi.emutran.data.device.InstalledAppsRegistry
import io.github.mayusi.emutran.data.manifest.AppEntry
import io.github.mayusi.emutran.data.manifest.ObtainiumPackParser
import io.github.mayusi.emutran.data.manifest.SystemTag
import io.github.mayusi.emutran.data.selection.SelectedAppsStore
import io.github.mayusi.emutran.data.storage.SetupOptionsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Loads the manifest, tracks user picks, exposes filter state.
 *
 * Loads the standard or dual-screen manifest depending on the
 * SetupOptionsStore.isDualScreen flag (seeded by auto-detection on the
 * Device Info screen, overridable by the user's toggle there).
 *
 * trackOnly entries (drivers, the Obtainium pack itself) used to be
 * filtered out at load time. We now include them because most are
 * regular GitHub releases that install just fine — the trackOnly flag
 * is for Obtainium's own update tracking, not a "can't install" hint.
 */
@HiltViewModel
class PickAppsViewModel @Inject constructor(
    private val parser: ObtainiumPackParser,
    private val selectedStore: SelectedAppsStore,
    private val installedApps: InstalledAppsRegistry,
    private val setupOptions: SetupOptionsStore,
) : ViewModel() {

    private val _ui = MutableStateFlow<UiState>(UiState.Loading)
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    /** Set of currently-checked entry IDs. */
    private val _picked = MutableStateFlow<Set<String>>(emptySet())
    val picked: StateFlow<Set<String>> = _picked.asStateFlow()

    /** Set of entry IDs the system reports as already installed. */
    private val _installed = MutableStateFlow<Set<String>>(emptySet())
    val installed: StateFlow<Set<String>> = _installed.asStateFlow()

    /** Current filter — controls what the picker shows. */
    private val _filter = MutableStateFlow<PickerFilter>(PickerFilter.All)
    val filter: StateFlow<PickerFilter> = _filter.asStateFlow()

    /** Free-text search query — composes WITH the category filter. */
    private val _search = MutableStateFlow("")
    val search: StateFlow<String> = _search.asStateFlow()

    init { load() }

    private fun load() {
        viewModelScope.launch {
            try {
                // Include trackOnly entries now — most are real GitHub
                // releases (the Adreno driver repos), only a few are
                // pure tracking targets like the Obtainium pack itself.
                // The Obtainium meta entry has a generic name and no
                // useful download surface, so filter just that one by id.
                // Dual-screen handhelds get a different manifest (extra
                // forks like Cemu/MelonDS dual-screen builds). The flag is
                // seeded from auto-detection and overridable on the Device
                // Info screen.
                val dualScreen = setupOptions.isDualScreen.first()
                val entries = withContext(Dispatchers.IO) {
                    if (dualScreen) parser.loadDualScreen() else parser.loadStandard()
                }.filterNot { it.id == "904332840" } // Obtainium pack meta

                refreshInstalled()
                val alreadyHere = _installed.value
                // Pre-check recommended entries that the user does NOT
                // already have — no point pre-selecting Flycast when
                // it's already on the device.
                val initial = entries
                    .filter { it.recommended && it.id !in alreadyHere }
                    .map { it.id }
                    .toSet()
                _picked.update { initial }
                _ui.update { UiState.Ready(entries) }
            } catch (t: Throwable) {
                _ui.update { UiState.Failed(t.message ?: t.javaClass.simpleName) }
            }
        }
    }

    /** Re-query installed packages. Call from screen ON_RESUME. */
    fun refreshInstalled() {
        _installed.update { installedApps.snapshot() }
    }

    fun toggle(entryId: String) {
        _picked.update { current ->
            if (entryId in current) current - entryId else current + entryId
        }
    }

    fun selectRecommended() {
        val ready = _ui.value as? UiState.Ready ?: return
        _picked.update {
            ready.allEntries.filter { it.recommended }.map { it.id }.toSet()
        }
    }

    fun selectNone() {
        _picked.update { emptySet() }
    }

    fun setFilter(filter: PickerFilter) {
        _filter.update { filter }
    }

    fun setSearch(query: String) {
        _search.update { query }
    }

    /** Persist the selection for the next screen to read. */
    fun commit() {
        viewModelScope.launch { selectedStore.save(_picked.value) }
    }

    sealed interface UiState {
        data object Loading : UiState
        data class Ready(val allEntries: List<AppEntry>) : UiState
        data class Failed(val message: String) : UiState
    }

    /** What the user wants to see in the picker right now. */
    sealed interface PickerFilter {
        data object All : PickerFilter
        data object Drivers : PickerFilter
        data class System(val tag: SystemTag) : PickerFilter
    }
}
