package io.github.mayusi.emutran.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.mayusi.emutran.data.storage.UiPrefsStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives the first-launch-only "Join our Discord" prompt.
 *
 * [showPrompt] is simply the inverse of the persisted
 * [UiPrefsStore.discordPromptShown] flag: true until the user has seen the
 * prompt once, false forever after. The flag defaults to false, so a brand-new
 * install shows the prompt exactly once.
 *
 * Flash-over-splash is handled by the caller ([EmuTranApp]), which only renders
 * the dialog after [AppBootstrapViewModel] has resolved a startDestination — so
 * the prompt can never appear on top of the blank bootstrap background.
 *
 * Both [onJoin] and [dismiss] flip the flag true so the prompt never returns.
 */
@HiltViewModel
class DiscordPromptViewModel @Inject constructor(
    private val uiPrefs: UiPrefsStore,
) : ViewModel() {

    /** True only while the first-launch prompt is still pending (flag == false). */
    val showPrompt: StateFlow<Boolean> = uiPrefs.discordPromptShown
        .map { shown -> !shown }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            // Start hidden: we don't yet know the flag, and showing-then-hiding
            // would flicker. The flow corrects to true within a frame on a fresh
            // install once DataStore emits its (false) default.
            initialValue = false,
        )

    /** User tapped "Join Discord" — record that the prompt has been shown. */
    fun onJoin() = markShown()

    /** User dismissed the prompt ("Maybe later" / back) — record it too. */
    fun dismiss() = markShown()

    private fun markShown() {
        viewModelScope.launch {
            uiPrefs.setDiscordPromptShown(true)
        }
    }
}
