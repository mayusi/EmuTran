package io.github.mayusi.emutran.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * State that re-runs [compute] every time the host enters ON_RESUME.
 *
 * Use this for permission checks where the user has bounced into Settings
 * (Manage all files, Install unknown apps, etc.) and we want the UI to
 * reflect the new state the moment they come back, without forcing them
 * to re-tap a button.
 */
@Composable
fun rememberOnResume(compute: () -> Boolean): MutableState<Boolean> {
    val owner = LocalLifecycleOwner.current
    val state = remember { mutableStateOf(compute()) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                state.value = compute()
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return state
}
