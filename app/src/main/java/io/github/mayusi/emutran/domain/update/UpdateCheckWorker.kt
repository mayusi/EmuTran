package io.github.mayusi.emutran.domain.update

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import io.github.mayusi.emutran.data.update.UpdateRepository
import io.github.mayusi.emutran.data.update.UpdateStateStore
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Periodic background worker that checks for emulator updates while the
 * user isn't actively using the app.
 *
 * Annotated with [@HiltWorker] and injected via [@AssistedInject] — the
 * standard Hilt-WorkManager pattern. The HiltWorkerFactory must be
 * configured in the Application class (done in [EmuTranApplication]).
 *
 * == Schedule ==
 *
 * Runs every 6 hours with a 15-minute flex window. Constraints:
 *   - NetworkType.CONNECTED — pointless without a network.
 *   - Battery not low — respect the device's battery-saving mode.
 *
 * [ExistingPeriodicWorkPolicy.KEEP] prevents duplicate scheduling if
 * [schedule] is called repeatedly (e.g. on every app launch).
 *
 * == What it does ==
 *
 * Calls [UpdateRepository.checkNow] with force=false so entries checked
 * less than 6h ago are skipped at the repository level too. The worker
 * itself is scheduled every 6h, but the repository guard is a second
 * line of defence against OS-level constraint drift.
 *
 * After the check completes, the worker compares the current set of
 * pending updates (by signature) against what was last notified. If the
 * signature differs — meaning a genuinely new update has appeared — it
 * posts a system notification via [UpdateNotifier] and saves the new
 * signature via [UpdateStateStore]. This prevents re-notifying for the
 * same versions on every 6-hour tick.
 *
 * Updates are NOT downloaded or installed by this worker — that would
 * be intrusive. It only refreshes the stored update state so the UI
 * can show a badge / chip when the user opens the app.
 */
@HiltWorker
class UpdateCheckWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val updateRepository: UpdateRepository,
    private val updateStateStore: UpdateStateStore,
    private val updateNotifier: UpdateNotifier,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            updateRepository.checkNow(force = false)

            // Read the freshly-written update state and build a stable
            // signature from every entry that currently has an update.
            val currentUpdates = updateRepository.updateState().first()
            val pending = currentUpdates.values.filter { it.hasUpdate }

            if (pending.isNotEmpty()) {
                // Signature = sorted "entryId:availableVersion" joined by "|".
                // Sorting ensures the signature is order-independent.
                val newSig = pending
                    .map { "${it.entryId}:${it.availableVersion.orEmpty()}" }
                    .sorted()
                    .joinToString("|")

                val lastSig = updateStateStore.getLastNotifiedSignature()

                if (newSig != lastSig) {
                    // Update set has changed since we last notified — post a
                    // fresh notification and record the new signature.
                    val sampleNames = pending
                        .sortedBy { it.entryId }
                        .map { it.entryId }          // fallback: entryId as display name
                    updateNotifier.notifyUpdatesAvailable(pending.size, sampleNames)
                    updateStateStore.setLastNotifiedSignature(newSig)
                }
                // If newSig == lastSig, the user was already notified about
                // this exact set — skip to avoid 6-hour spam.
            }
            // If pending is empty we leave the signature alone. The notification
            // was presumably dismissed by the user or cancelled when they
            // installed an update. NotificationManagerCompat auto-cancel
            // (setAutoCancel=true on the notification) handles dismissal on tap.

            Result.success()
        } catch (t: Throwable) {
            // Retry on transient failures (network unavailable, rate limit,
            // etc.). WorkManager will back off and retry up to its limit.
            Result.retry()
        }
    }

    companion object {
        /** Unique name prevents multiple instances of this periodic work. */
        const val WORK_NAME = "emutran_update_check_periodic"

        /**
         * Enqueue the periodic update-check worker, or do nothing if it is
         * already scheduled ([ExistingPeriodicWorkPolicy.KEEP]).
         *
         * Call this once from [EmuTranApplication.onCreate] or a startup
         * initializer. Safe to call on every launch.
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()

            val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(
                repeatInterval = 6L,
                repeatIntervalTimeUnit = TimeUnit.HOURS,
            )
                .setConstraints(constraints)
                .setInitialDelay(15L, TimeUnit.MINUTES) // don't fire immediately at first launch
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
