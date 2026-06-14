package io.github.mayusi.emutran.ui.deviceinfo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.mayusi.emutran.data.device.DeviceDetector
import io.github.mayusi.emutran.data.device.DeviceProfile
import io.github.mayusi.emutran.data.storage.SetupOptionsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DeviceInfoViewModel @Inject constructor(
    detector: DeviceDetector,
    private val setupOptions: SetupOptionsStore,
) : ViewModel() {
    private val _profile = MutableStateFlow(detector.detect())
    val profile: StateFlow<DeviceProfile> = _profile.asStateFlow()

    private val _userOverrideDualScreen = MutableStateFlow<Boolean?>(null)
    val userOverrideDualScreen: StateFlow<Boolean?> = _userOverrideDualScreen.asStateFlow()

    init {
        // Seed the persisted flag with the auto-detected guess so the
        // picker/dashboard load the right manifest even if the user never
        // touches the toggle. An explicit user choice (below) overwrites it.
        viewModelScope.launch {
            setupOptions.setIsDualScreen(_profile.value.isDualScreenGuess)
        }
    }

    fun setDualScreen(value: Boolean) {
        _userOverrideDualScreen.update { value }
        // Persist immediately so downstream screens read the user's choice.
        viewModelScope.launch { setupOptions.setIsDualScreen(value) }
    }

    /** Final answer: explicit user choice wins, otherwise auto-detected guess. */
    val effectiveDualScreen: Boolean
        get() = _userOverrideDualScreen.value ?: _profile.value.isDualScreenGuess
}
