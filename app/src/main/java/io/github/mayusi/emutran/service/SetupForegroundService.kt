package io.github.mayusi.emutran.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import io.github.mayusi.emutran.MainActivity
import io.github.mayusi.emutran.R
import io.github.mayusi.emutran.ui.progress.ProgressViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground service that keeps the download/install pipeline alive and
 * surfaces an ongoing progress notification in the shade.
 *
 * Lifecycle:
 *  - ProgressViewModel starts this service when the run begins.
 *  - The service collects [SetupServiceBridge.state] (injected via Hilt) and
 *    stops itself when it sees a terminal state: Done, Failed, or Cancelled.
 *  - On Done/Failed the notification is updated before [stopSelf]; on Cancelled
 *    the notification is cancelled immediately so no "Setting up…" lingers.
 *
 * FIX 4 (decouple): replaced the static @Volatile pendingStateFlow with an
 * injected [SetupServiceBridge] singleton. This eliminates the race condition
 * on retry and makes the bridge testable.
 *
 * FIX 4 (SupervisorJob): the coroutine scope uses SupervisorJob so a child
 * coroutine failure does not tear down the whole scope (audit M-5).
 *
 * FIX 4 (contentIntent): tapping the notification navigates back to MainActivity
 * instead of doing nothing.
 *
 * FIX 1 (Cancelled terminal): Cancelled is now treated the same as Done/Failed —
 * the service stops itself and the notification is dismissed so the user never
 * sees a stale "Setting up…" notification after cancelling.
 */
@AndroidEntryPoint
class SetupForegroundService : Service() {

    @Inject lateinit var bridge: SetupServiceBridge

    // SupervisorJob: child failure does not cancel sibling coroutines or the scope.
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private var observeJob: Job? = null

    // Throttle notification updates to at most once per 500 ms so we don't
    // hammer the NotificationManager IPC even on un-throttled upstream emissions.
    private var lastNotifyMs = 0L

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Build the tap-to-return-to-app PendingIntent (FIX 4).
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pendingTap = PendingIntent.getActivity(
            this, 0, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // Promote to foreground immediately so the OS doesn't kill us before
        // we get a chance to start observing state.
        startForeground(
            NOTIFICATION_ID,
            buildNotification(this, "Setting up…", -1, -1, pendingTap),
        )

        // Restart the observation job each time the service is (re-)started,
        // picking up whatever state the bridge currently holds.
        observeJob?.cancel()
        observeJob = scope.launch {
            bridge.state.collect { state ->
                val isTerminal = state is ProgressViewModel.State.Done ||
                    state is ProgressViewModel.State.Failed ||
                    state is ProgressViewModel.State.Cancelled          // FIX 1

                // Throttle: emit at most once per 500 ms, except terminal states
                // which are always delivered immediately.
                val now = System.currentTimeMillis()
                if (isTerminal || (now - lastNotifyMs) >= 500L) {
                    lastNotifyMs = now

                    if (state is ProgressViewModel.State.Cancelled) {
                        // FIX 1: dismiss the notification immediately on cancel —
                        // no "Setup failed" stub, just clean removal.
                        val manager =
                            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        manager.cancel(NOTIFICATION_ID)
                    } else {
                        updateNotification(state, pendingTap)
                    }
                }

                if (isTerminal) stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---- notification helpers ----

    private fun updateNotification(
        state: ProgressViewModel.State,
        pendingTap: PendingIntent,
    ) {
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
                Triple(
                    "Installing ${state.done + 1}/${state.total} — ${state.currentApp}",
                    state.done, state.total,
                )
            is ProgressViewModel.State.StagingDrivers ->
                Triple("Setting up GPU drivers…", -1, -1)
            is ProgressViewModel.State.OfflineWarning ->
                Triple("No network — waiting for decision…", -1, -1)
            is ProgressViewModel.State.Done ->
                Triple("Setup complete", -1, -1)
            is ProgressViewModel.State.Failed ->
                Triple("Setup failed", -1, -1)
            // Cancelled is handled before this call — should never reach here.
            is ProgressViewModel.State.Cancelled ->
                Triple("Cancelled", -1, -1)
        }
        manager.notify(NOTIFICATION_ID, buildNotification(this, text, done, total, pendingTap))
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "emutran_setup"

        fun ensureNotificationChannel(context: Context) {
            val manager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                "EmuTran Setup",
                NotificationManager.IMPORTANCE_LOW,  // no sound/heads-up — just shade progress
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
            contentIntent: PendingIntent? = null,
        ): Notification {
            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("EmuTran — Setting up…")
                .setContentText(contentText)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

            contentIntent?.let { builder.setContentIntent(it) }

            if (progressTotal > 0) {
                builder.setProgress(progressTotal, progressDone, false)
            } else {
                builder.setProgress(0, 0, true)  // indeterminate
            }
            return builder.build()
        }
    }
}
