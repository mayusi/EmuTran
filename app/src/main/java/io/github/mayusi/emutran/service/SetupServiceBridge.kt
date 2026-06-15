package io.github.mayusi.emutran.service

import io.github.mayusi.emutran.ui.progress.ProgressViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hilt-managed bridge that lets [io.github.mayusi.emutran.ui.progress.ProgressViewModel]
 * publish its state to [SetupForegroundService] without a static mutable field.
 *
 * Why this instead of the old static var?
 * - The static @Volatile var is a race on retry: if the VM is re-created (process
 *   death, retryAll) before the previous service [onDestroy] clears it, the service
 *   starts collecting the *new* flow while briefly holding a stale reference — or
 *   vice-versa. An injectable singleton is shared by reference, so the VM's write
 *   is immediately visible to the service on the same coroutine dispatcher.
 * - It is testable: tests can inject a fake bridge.
 *
 * Usage:
 *   ProgressViewModel: call [publish] before starting the service.
 *   SetupForegroundService: collect [state].
 */
@Singleton
class SetupServiceBridge @Inject constructor() {

    private val _state = MutableStateFlow<ProgressViewModel.State>(ProgressViewModel.State.Idle)

    /** The live setup state. [SetupForegroundService] collects this. */
    val state: StateFlow<ProgressViewModel.State> = _state.asStateFlow()

    /**
     * Called by [io.github.mayusi.emutran.ui.progress.ProgressViewModel] to push
     * its own [MutableStateFlow] value into the bridge on every state change.
     *
     * Rather than the VM writing to a separate flow, we expose the bridge's own
     * [MutableStateFlow] directly so the VM can update it atomically with its own
     * internal [_state] in the same [update] call.
     */
    internal val mutable: MutableStateFlow<ProgressViewModel.State> get() = _state
}
