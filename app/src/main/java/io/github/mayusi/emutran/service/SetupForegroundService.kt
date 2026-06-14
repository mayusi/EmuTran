package io.github.mayusi.emutran.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import io.github.mayusi.emutran.R
import io.github.mayusi.emutran.ui.progress.ProgressViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the download/install pipeline alive and
 * surfaces an ongoing progress notification in the shade.
 *
 * Lifecycle:
 *  - ProgressViewModel calls [startForState] (via [Context.startForegroundService])
 *    when the run begins, passing the state flow as a sticky extra is not
 *    possible, so the VM calls [bindProgressFlow] after binding — but for
 *    simplicity we use a singleton companion object slot. The service stops
 *    itself when it sees State.Done or State.Failed.
 *
 * Why a foreground service and not WorkManager?
 *  - The install step requires UI / PackageInstaller dialogs tied to the
 *    Activity. Full WorkManager background job can't drive those. A foreground
 *    service keeps the process at foreground importance (preventing the OS
 *    from killing it mid-download) while leaving the Activity/ViewModel in
 *    control of the actual pipeline logic.
 *
 * The notification channel is created lazily on first start so we don't
 * need to do it in Application#onCreate.
 */
@AndroidEntryPoint
class SetupForegroundService : Service() {

    private val scope = CoroutineScope(Dispatchers.Main.immediate + Job())
    private var observeJob: Job? = null
    // FIX 1: throttle notification updates to at most once per 500 ms so we
    // don't hammer the NotificationManager IPC even on un-throttled upstream emissions.
    private var lastNotifyMs = 0L

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Promote to foreground immediately so the OS doesn't kill us before
        // we get a chance to start observing state.
        startForeground(NOTIFICATION_ID, buildNotification(this, "Setting up…", -1, -1))

        // Start observing the shared state flow if one was registered.
        val stateFlow = pendingStateFlow
        if (stateFlow != null) {
            observeJob?.cancel()
            observeJob = scope.launch {
                stateFlow.collect { state ->
                    val isTerminal = state is ProgressViewModel.State.Done ||
                        state is ProgressViewModel.State.Failed
                    // FIX 1: guard notification updates to at most once per 500 ms.
                    // Terminal states (Done/Failed) are always emitted immediately so
                    // the user sees the final outcome without an artificial delay.
                    val now = System.currentTimeMillis()
                    if (isTerminal || (now - lastNotifyMs) >= 500L) {
                        lastNotifyMs = now
                        updateNotification(state)
                    }
                    if (isTerminal) stopSelf()
                }
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        pendingStateFlow = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---- notification helpers ----

    private fun updateNotification(state: ProgressViewModel.State) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val (text, done, total) = when (state) {
            is ProgressViewModel.State.Idle ->
                Triple("Preparing…", -1, -1)
            is ProgressViewModel.State.Scaffolding ->
                Triple("Building folders… ${state.done}/${state.total}", state.done, state.total)
            is ProgressViewModel.State.Resolving ->
                Triple("Looking up downloads… ${state.done}/${state.total}", state.done, state.total)
            is ProgressViewModel.State.Downloading ->
                Triple(
                    "Downloading ${state.done + 1}/${state.total} — ${state.currentApp}",
                    state.done, state.total,
                )
            is ProgressViewModel.State.Installing ->
                Triple("Installing ${state.done + 1}/${state.total} — ${state.currentApp}", state.done, state.total)
            is ProgressViewModel.State.StagingDrivers ->
                Triple("Setting up GPU drivers…", -1, -1)
            is ProgressViewModel.State.OfflineWarning ->
                Triple("No network — waiting for decision…", -1, -1)
            is ProgressViewModel.State.Done ->
                Triple("Setup complete", -1, -1)
            is ProgressViewModel.State.Failed ->
                Triple("Setup failed", -1, -1)
        }
        manager.notify(NOTIFICATION_ID, buildNotification(this, text, done, total))
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "emutran_setup"

        /**
         * Static slot for the ProgressViewModel to register its state flow
         * before starting the service. Cleared in onDestroy().
         *
         * This is simpler than a Binder/AIDL interface for our use-case —
         * the ViewModel and Service are in the same process so a static
         * reference is safe and avoids boilerplate.
         */
        @Volatile
        var pendingStateFlow: StateFlow<ProgressViewModel.State>? = null

        fun ensureNotificationChannel(context: Context) {
            val manager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                "EmuTran Setup",
                NotificationManager.IMPORTANCE_LOW,   // no sound/heads-up — just shade progress
            ).apply {
                description = "Shows progress while EmuTran is downloading and installing apps."
                setSound(null, null)
                enableVibration(false)
            }
            manager.createNotificationChannel(channel)
        }

        fun buildNotification(
            context: Context,
            contentText: String,
            progressDone: Int,
            progressTotal: Int,
        ): Notification {
            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("EmuTran — Setting up…")
                .setContentText(contentText)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

            if (progressTotal > 0) {
                builder.setProgress(progressTotal, progressDone, false)
            } else {
                builder.setProgress(0, 0, true)  // indeterminate
            }
            return builder.build()
        }
    }
}
