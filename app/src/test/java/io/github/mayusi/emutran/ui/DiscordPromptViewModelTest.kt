package io.github.mayusi.emutran.ui

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.github.mayusi.emutran.data.storage.UiPrefsStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [DiscordPromptViewModel] — the first-launch-only Discord prompt
 * gate.
 *
 * The persisted flag lives in [UiPrefsStore], which is DataStore-backed and not
 * unit-testable on the JVM without Robolectric. So the store is MockK'd: its
 * [UiPrefsStore.discordPromptShown] flow is backed by a [MutableStateFlow] we
 * drive directly, and [UiPrefsStore.setDiscordPromptShown] flips that same flow
 * so the round-trip behaves like the real store.
 *
 * Properties verified:
 *  - flag false  -> showPrompt becomes true (first launch shows the prompt);
 *  - flag true   -> showPrompt stays false (already seen, never re-shown);
 *  - onJoin()    -> persists shown=true, so showPrompt drops to false;
 *  - dismiss()   -> same.
 *
 * The Main dispatcher is replaced with an [UnconfinedTestDispatcher] so
 * [androidx.lifecycle.viewModelScope] launches and the stateIn(WhileSubscribed)
 * collection run eagerly and deterministically.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DiscordPromptViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    /** Backing flow standing in for the persisted DataStore flag. */
    private val shownFlow = MutableStateFlow(false)

    private val uiPrefs = mockk<UiPrefsStore> {
        every { discordPromptShown } returns shownFlow
        coEvery { setDiscordPromptShown(any()) } answers {
            shownFlow.value = firstArg()
        }
    }

    private fun viewModel() = DiscordPromptViewModel(uiPrefs)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `first launch shows the prompt when flag is false`() = runTest {
        shownFlow.value = false

        viewModel().showPrompt.test {
            // Flag is false (not yet shown) -> showPrompt is its inverse: true.
            // The StateFlow conflates the transient initial, so the settled
            // value the UI observes is true.
            assertThat(awaitItem()).isTrue()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `prompt does not show when flag is already true`() = runTest {
        shownFlow.value = true

        viewModel().showPrompt.test {
            // Flag already true -> showPrompt is the inverse, false, and never
            // becomes true.
            assertThat(awaitItem()).isFalse()
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onJoin persists shown and hides the prompt`() = runTest {
        shownFlow.value = false
        val vm = viewModel()

        vm.showPrompt.test {
            // Prompt is pending on a fresh install.
            assertThat(awaitItem()).isTrue()

            vm.onJoin()

            // Flag flips true -> showPrompt drops back to false, for good.
            assertThat(awaitItem()).isFalse()
            cancelAndIgnoreRemainingEvents()
        }

        assertThat(shownFlow.value).isTrue()
        coVerify(exactly = 1) { uiPrefs.setDiscordPromptShown(true) }
    }

    @Test
    fun `dismiss persists shown and hides the prompt`() = runTest {
        shownFlow.value = false
        val vm = viewModel()

        vm.showPrompt.test {
            assertThat(awaitItem()).isTrue() // prompt pending

            vm.dismiss()

            assertThat(awaitItem()).isFalse()
            cancelAndIgnoreRemainingEvents()
        }

        assertThat(shownFlow.value).isTrue()
        coVerify(exactly = 1) { uiPrefs.setDiscordPromptShown(true) }
    }
}
