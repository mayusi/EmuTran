package io.github.mayusi.emutran.ui.pickapps

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.mayusi.emutran.data.manifest.ObtainiumPackParser
import io.github.mayusi.emutran.data.selection.SelectedAppsStore
import io.github.mayusi.emutran.data.storage.SetupOptionsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Handles the "Quick Setup" one-tap path.
 *
 * Selects all recommended entries from the manifest and persists them
 * to [SelectedAppsStore] so [ProgressScreen] can start immediately —
 * the user never has to visit the manual picker.
 *
 * Usage:
 *  1. Call [commitRecommended].
 *  2. Observe [state] — when it transitions to [State.Ready], navigate
 *     to Routes.PROGRESS.
 */
@HiltViewModel
class QuickSetupViewModel @Inject constructor(
    private val parser: ObtainiumPackParser,
    private val selectedStore: SelectedAppsStore,
    private val setupOptions: SetupOptionsStore,
) : ViewModel() {

    sealed interface State {
        data object Idle : State
        data object Working : State
        data object Ready : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * Load the manifest (respecting the dual-screen option), select all
     * entries where [recommended] == true, persist, then set state to Ready.
     */
    fun commitRecommended() {
        if (_state.value is State.Working) return
        _state.value = State.Working
        viewModelScope.launch {
            try {
                val dualScreen = setupOptions.isDualScreen.first()
                val entries = withContext(Dispatchers.IO) {
                    if (dualScreen) parser.loadDualScreen() else parser.loadStandard()
                }.filterNot { it.id == "904332840" } // Obtainium pack meta

                val recommended = entries
                    .filter { it.recommended }
                    .map { it.id }
                    .toSet()

                selectedStore.save(recommended)
                _state.value = State.Ready
            } catch (t: Throwable) {
                // FIX 7: keep State.Failed visible so EmuTranApp's LaunchedEffect
                // can observe it and show a snackbar. The LaunchedEffect calls
                // reset() after the snackbar is shown. Do NOT immediately overwrite
                // with Idle here — that race prevents the UI from ever seeing Failed.
                _state.value = State.Failed(t.message ?: t.javaClass.simpleName)
            }
        }
    }

    /** Reset so the ViewModel can be reused if the user navigates back. */
    fun reset() {
        _state.value = State.Idle
    }
}
