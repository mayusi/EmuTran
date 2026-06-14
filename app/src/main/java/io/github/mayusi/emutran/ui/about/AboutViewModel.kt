package io.github.mayusi.emutran.ui.about

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.mayusi.emutran.data.auth.GithubTokenStore
import io.github.mayusi.emutran.data.update.SelfUpdateRepository
import io.github.mayusi.emutran.data.update.SelfUpdateResult
import io.github.mayusi.emutran.domain.download.ApkDownloader
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives the About screen's self-update check and install flow, and the
 * optional GitHub PAT (Personal Access Token) configuration.
 *
 * Self-update states:
 *   [SelfUpdateUiState.Idle]        — no check in progress.
 *   [SelfUpdateUiState.Checking]    — network request in flight.
 *   [SelfUpdateUiState.Available]   — update found; bottom sheet visible.
 *   [SelfUpdateUiState.Downloading] — APK download in progress (0–100%).
 *   [SelfUpdateUiState.Launching]   — system installer launched; waiting for user.
 *   [SelfUpdateUiState.UpToDate]    — latest version; show snackbar.
 *   [SelfUpdateUiState.Failed]      — error; message to show in snackbar.
 *
 * The sheet is dismissed via [dismissSheet], which cancels any in-flight
 * download and resets state to Idle.
 *
 * GitHub token: exposed via [githubToken] (null when unset). [setGithubToken]
 * persists the value; [clearGithubToken] removes it.
 */
@HiltViewModel
class AboutViewModel @Inject constructor(
    private val selfUpdateRepository: SelfUpdateRepository,
    private val githubTokenStore: GithubTokenStore,
) : ViewModel() {

    /** Current stored GitHub PAT, or null when none is set. */
    val githubToken: StateFlow<String?> = githubTokenStore.token.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = githubTokenStore.currentToken(),
    )

    /** Persist [token] (trimmed). Pass null or blank to clear. */
    fun setGithubToken(token: String?) {
        viewModelScope.launch {
            githubTokenStore.setToken(token)
        }
    }

    /** Remove any stored GitHub PAT. */
    fun clearGithubToken() {
        viewModelScope.launch {
            githubTokenStore.setToken(null)
        }
    }

    private val _uiState = MutableStateFlow<SelfUpdateUiState>(SelfUpdateUiState.Idle)
    val uiState: StateFlow<SelfUpdateUiState> = _uiState.asStateFlow()

    // FIX 1: Track the active download job so dismissSheet() can cancel it.
    // Without this, a dismissed sheet would let the download finish and then
    // launch the system installer behind the user's back.
    private var downloadJob: Job? = null

    /**
     * Initiate a forced self-update check. Transitions through:
     *   Idle → Checking → (Available | UpToDate | Failed)
     */
    fun checkForSelfUpdate() {
        if (_uiState.value is SelfUpdateUiState.Checking) return
        _uiState.value = SelfUpdateUiState.Checking
        viewModelScope.launch {
            try {
                val result = selfUpdateRepository.checkSelfUpdate(force = true)
                _uiState.value = when (result) {
                    is SelfUpdateResult.Available -> SelfUpdateUiState.Available(
                        version = result.version,
                        changelog = result.changelogMarkdown,
                        apkUrl = result.apkUrl,
                        sha256Url = result.sha256Url,
                    )
                    is SelfUpdateResult.UpToDate -> SelfUpdateUiState.UpToDate
                    is SelfUpdateResult.Failed   -> SelfUpdateUiState.Failed(result.reason)
                }
            } catch (e: Throwable) {
                _uiState.value = SelfUpdateUiState.Failed(
                    e.message ?: e.javaClass.simpleName
                )
            }
        }
    }

    /**
     * Start downloading + installing the APK at [apkUrl].
     * Transitions: Available → Downloading(progress) → Launching.
     *
     * The SHA-256 sidecar URL is automatically taken from the current
     * [SelfUpdateUiState.Available] state (where it was stored by
     * [checkForSelfUpdate]). The caller (AboutScreen) only needs to pass
     * [apkUrl]; no change to the existing screen call-site is required.
     *
     * When a sha256Url is available, the downloader fetches the sidecar and
     * verifies the file's integrity before launching the installer. On checksum
     * mismatch the downloader emits [ApkDownloader.Progress.Failed] and the
     * installer is never reached.
     *
     * FIX 1: The Job is stored in [downloadJob] so that [dismissSheet] can
     * cancel it. Cancelling the coroutine before [ApkDownloader.Progress.Done]
     * is collected means [launchInstaller] is never reached in the repository.
     */
    fun downloadAndInstall(apkUrl: String) {
        // Retrieve the sha256Url from the current Available state, if present.
        // This is the correct place to read it: the state is Available at the
        // moment the user taps "Update now" and it won't change until the
        // coroutine below transitions it to Downloading.
        val sha256Url = (_uiState.value as? SelfUpdateUiState.Available)?.sha256Url

        // FIX 1: Store the Job so dismissSheet() can cancel an in-flight download.
        downloadJob = viewModelScope.launch {
            try {
                selfUpdateRepository.downloadAndInstall(apkUrl, sha256Url).collect { progress ->
                    when (progress) {
                        is ApkDownloader.Progress.Started ->
                            _uiState.value = SelfUpdateUiState.Downloading(0)
                        is ApkDownloader.Progress.Chunk -> {
                            val pct = if (progress.totalBytes > 0L) {
                                (progress.downloaded * 100 / progress.totalBytes).toInt()
                            } else 0
                            _uiState.value = SelfUpdateUiState.Downloading(pct)
                        }
                        is ApkDownloader.Progress.Done ->
                            _uiState.value = SelfUpdateUiState.Launching
                        is ApkDownloader.Progress.Failed ->
                            _uiState.value = SelfUpdateUiState.Failed(progress.message)
                    }
                }
            } catch (e: Throwable) {
                _uiState.value = SelfUpdateUiState.Failed(
                    e.message ?: e.javaClass.simpleName
                )
            }
        }
    }

    /**
     * Dismiss the update bottom sheet and return to [SelfUpdateUiState.Idle].
     * Also resets Launching/UpToDate/Failed so the snackbar can be re-triggered.
     *
     * FIX 1: Cancels [downloadJob] if a download is in flight, preventing the
     * system installer from launching after the user dismisses the sheet.
     */
    fun dismissSheet() {
        // Cancel any in-flight download before resetting state.
        downloadJob?.cancel()
        downloadJob = null

        _uiState.update { current ->
            // Keep Idle if already there; reset any terminal/transient state.
            if (current is SelfUpdateUiState.Idle) current else SelfUpdateUiState.Idle
        }
    }

    sealed interface SelfUpdateUiState {
        data object Idle        : SelfUpdateUiState
        data object Checking    : SelfUpdateUiState
        data class Available(
            val version: String,
            val changelog: String,
            val apkUrl: String,
            /** Sidecar SHA-256 URL forwarded from [SelfUpdateResult.Available.sha256Url]. */
            val sha256Url: String?,
        ) : SelfUpdateUiState
        data class Downloading(val percent: Int) : SelfUpdateUiState
        data object Launching   : SelfUpdateUiState
        data object UpToDate    : SelfUpdateUiState
        data class Failed(val reason: String) : SelfUpdateUiState
    }
}
