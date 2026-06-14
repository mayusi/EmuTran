package io.github.mayusi.emutran.ui.health

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.mayusi.emutran.domain.health.HealthCheckResult
import io.github.mayusi.emutran.domain.health.HealthChecker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Setup Health Check screen.
 *
 * Exposes a single [StateFlow] of [HealthUiState] that the screen observes.
 * Checks are run automatically on first composition (via [init]) and can be
 * re-triggered by the user via [recheck].
 *
 * All heavy work is delegated to [HealthChecker], which runs on Dispatchers.IO.
 */
@HiltViewModel
class HealthCheckViewModel @Inject constructor(
    private val healthChecker: HealthChecker,
) : ViewModel() {

    /** UI state for the Health Check screen. */
    sealed interface HealthUiState {
        /** Checks are in progress — show a loading spinner. */
        data object Loading : HealthUiState

        /** Checks completed — show the result list. */
        data class Ready(val results: List<HealthCheckResult>) : HealthUiState
    }

    private val _state = MutableStateFlow<HealthUiState>(HealthUiState.Loading)

    /** Observed by [HealthCheckScreen] to drive the UI. */
    val state: StateFlow<HealthUiState> = _state.asStateFlow()

    init {
        // Kick off the initial check run as soon as the ViewModel is created.
        runChecks()
    }

    /**
     * Re-runs all health checks, resetting to Loading state first so the user
     * sees the spinner again. Safe to call multiple times — each call cancels
     * nothing; viewModelScope ensures the coroutine is tied to ViewModel
     * lifecycle and cancelled on clear.
     */
    fun recheck() {
        runChecks()
    }

    private fun runChecks() {
        viewModelScope.launch {
            _state.value = HealthUiState.Loading
            val results = healthChecker.runChecks()
            _state.value = HealthUiState.Ready(results)
        }
    }
}
