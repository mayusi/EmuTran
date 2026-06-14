package io.github.mayusi.emutran.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.mayusi.emutran.data.setup.SetupStateDetector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Top-level bootstrap: decides which screen to start the NavHost on.
 *
 * Runs once at activity start. Until SetupStateDetector reports back,
 * the UI shows the splash screen (cheap, brand-y, hides the latency).
 * Once we know, we route either to the splash setup flow (first run)
 * or straight to the dashboard.
 */
@HiltViewModel
class AppBootstrapViewModel @Inject constructor(
    private val detector: SetupStateDetector,
) : ViewModel() {

    private val _startDestination = MutableStateFlow<String?>(null)
    val startDestination: StateFlow<String?> = _startDestination.asStateFlow()

    init {
        viewModelScope.launch {
            val setUp = detector.isSetUp()
            _startDestination.update {
                if (setUp) Routes.DASHBOARD else Routes.SPLASH
            }
        }
    }
}
