package io.github.mayusi.emutran.ui.about

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.mayusi.emutran.data.auth.GithubTokenStore
import io.github.mayusi.emutran.data.update.SelfUpdateProgress
import io.github.mayusi.emutran.data.update.SelfUpdateRepository
import io.github.mayusi.emutran.data.update.SelfUpdateResult
import io.github.mayusi.emutran.ui.common.DOWNLOAD_EMIT_THROTTLE_MS
import kotlinx.coroutines.CancellationException
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
 *   [SelfUpdateUiState.Idle]            — no check in progress.
 *   [SelfUpdateUiState.Checking]        — network request in flight.
 *   [SelfUpdateUiState.Available]       — update found; bottom sheet visible.
 *   [SelfUpdateUiState.Downloading]     — APK download in progress (0–100%).
 *   [SelfUpdateUiState.Launching]       — system installer launched; waiting for user.
 *   [SelfUpdateUiState.NeedsInstallPermission] — "Install unknown apps" grant
 *       missing; UI must deep-link to settings and let the user retry (DEFECT 1).
 *   [SelfUpdateUiState.UpToDate]        — latest version; show snackbar.
 *   [SelfUpdateUiState.Failed]          — error; message to show in snackbar.
 *
 * FIX 4 changes:
 *   - [SelfUpdateUiState.Downloading] now carries [version] so the shared
 *     sheet title doesn't lose the version string during download.
 *   - [downloadAndInstall] throttles Chunk→Downloading state emissions to
 *     ~200ms to avoid recomposing the sheet on every 64 KB chunk.
 *   - [CancellationException] from a user-dismissed download is caught and
 *     state is reset silently (no "Update failed" snackbar).
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
     * FIX 4 (throttle): Chunk events are throttled to ~200ms so every 64 KB
     * chunk doesn't cause a full recompose of the sheet.
     *
     * FIX 4 (version): the version string is read from [SelfUpdateUiState.Available]
     * before the state transitions to Downloading, so the Downloading state can
     * carry it and the title "What's new in vX" stays correct.
     *
     * FIX 4 (CancellationException): user-cancelled downloads are reset to Idle
     * silently; the exception is re-thrown for structured concurrency.
     */
    fun downloadAndInstall(apkUrl: String) {
        val currentAvailable = _uiState.value as? SelfUpdateUiState.Available
        val sha256Url = currentAvailable?.sha256Url
        // FIX 4: capture version before transitioning to Downloading.
        val version = currentAvailable?.version ?: ""

        downloadJob = viewModelScope.launch {
            try {
                var lastEmitMs = 0L
                selfUpdateRepository.downloadAndInstall(apkUrl, sha256Url).collect { progress ->
                    when (progress) {
                        is SelfUpdateProgress.Started ->
                            _uiState.value = SelfUpdateUiState.Downloading(0, version)
                        is SelfUpdateProgress.Chunk -> {
                            // FIX 4 throttle: emit at most once per 200ms.
                            val now = System.currentTimeMillis()
                            if (now - lastEmitMs >= DOWNLOAD_EMIT_THROTTLE_MS) {
                                lastEmitMs = now
                                val pct = if (progress.totalBytes > 0L) {
                                    (progress.downloaded * 100 / progress.totalBytes).toInt()
                                } else 0
                                _uiState.value = SelfUpdateUiState.Downloading(pct, version)
                            }
                        }
                        is SelfUpdateProgress.Done ->
                            _uiState.value = SelfUpdateUiState.Launching
                        // DEFECT 1: surface the missing-permission case as a distinct
                        // state so the screen can offer a "Open settings" action and
                        // keep the captured [apkUrl] for a retry after the grant.
                        is SelfUpdateProgress.NeedsInstallPermission ->
                            _uiState.value = SelfUpdateUiState.NeedsInstallPermission(apkUrl)
                        is SelfUpdateProgress.Failed ->
                            _uiState.value = SelfUpdateUiState.Failed(progress.message)
                    }
                }
            } catch (e: CancellationException) {
                // FIX 4: user dismissed the sheet — reset to Idle silently.
                _uiState.value = SelfUpdateUiState.Idle
                throw e  // rethrow for structured concurrency
            } catch (e: Throwable) {
                _uiState.value = SelfUpdateUiState.Failed(
                    e.message ?: e.javaClass.simpleName
                )
            }
        }
    }

    /**
     * DEFECT 1: deep-link the user to the per-app "Install unknown apps" settings
     * page. Called from the screen when the state is
     * [SelfUpdateUiState.NeedsInstallPermission]. The state (and its captured
     * apkUrl) is left intact so the user can grant the permission, return, and
     * tap "Retry" to resume the install from the cached APK.
     */
    fun openInstallPermissionSettings() {
        selfUpdateRepository.openInstallPermissionSettings()
    }

    /**
     * Dismiss the update bottom sheet and return to [SelfUpdateUiState.Idle].
     * Cancels [downloadJob] if a download is in flight.
     */
    fun dismissSheet() {
        downloadJob?.cancel()
        downloadJob = null

        _uiState.update { current ->
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
        // FIX 4: Downloading now carries the version string so the sheet title
        // "What's new in vX" doesn't regress to "What's new in v" mid-download.
        data class Downloading(val percent: Int, val version: String) : SelfUpdateUiState
        data object Launching   : SelfUpdateUiState
        // DEFECT 1: the OS blocked the install because "Install unknown apps" is
        // off for this app. Carries [apkUrl] so the screen can retry the same
        // download once the user grants the permission.
        data class NeedsInstallPermission(val apkUrl: String) : SelfUpdateUiState
        data object UpToDate    : SelfUpdateUiState
        data class Failed(val reason: String) : SelfUpdateUiState
    }
}
