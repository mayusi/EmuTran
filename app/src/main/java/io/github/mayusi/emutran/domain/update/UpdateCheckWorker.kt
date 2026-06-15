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
import io.github.mayusi.emutran.data.manifest.ObtainiumPackParser
import io.github.mayusi.emutran.data.storage.SetupOptionsStore
import io.github.mayusi.emutran.data.update.UpdateRepository
import io.github.mayusi.emutran.data.update.UpdateStateStore
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Periodic background worker that checks for emulator updates while the
 * user isn't actively using the app.
 *
 * == FIX 2: display names in notifications ==
 *
 * Previously the notification body used raw entryIds (package names).
 * Now the worker loads the manifest, builds an id→name map, and passes
 * friendly display names to [UpdateNotifier.notifyUpdatesAvailable].
 * Manifest loading is cheap — the parser has an in-memory cache and the
 * result is reused from the app's normal manifest load if already warm.
 */
@HiltWorker
class UpdateCheckWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val updateRepository: UpdateRepository,
    private val updateStateStore: UpdateStateStore,
    private val updateNotifier: UpdateNotifier,
    // FIX 2: inject manifest parser + options to resolve display names.
    private val packParser: ObtainiumPackParser,
    private val setupOptions: SetupOptionsStore,
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
                val newSig = pending
                    .map { "${it.entryId}:${it.availableVersion.orEmpty()}" }
                    .sorted()
                    .joinToString("|")

                val lastSig = updateStateStore.getLastNotifiedSignature()

                if (newSig != lastSig) {
                    // FIX 2: build id→name map from the manifest so we show
                    // "Dolphin, PPSSPP" instead of "org.dolphinemu.dolphinemu, org.ppsspp.ppsspp".
                    val isDual = setupOptions.isDualScreen.first()
                    val entries = if (isDual) packParser.loadDualScreen() else packParser.loadStandard()
                    val nameMap: Map<String, String> = entries.associate { it.id to it.name }

                    val displayNames = pending
                        .sortedBy { it.entryId }
                        .map { nameMap[it.entryId] ?: it.entryId }  // fallback: raw id

                    updateNotifier.notifyUpdatesAvailable(pending.size, displayNames)
                    updateStateStore.setLastNotifiedSignature(newSig)
                }
            }

            Result.success()
        } catch (t: Throwable) {
            Result.retry()
        }
    }

    companion object {
        /** Unique name prevents multiple instances of this periodic work. */
        const val WORK_NAME = "emutran_update_check_periodic"

        /**
         * Enqueue the periodic update-check worker, or do nothing if it is
         * already scheduled ([ExistingPeriodicWorkPolicy.KEEP]).
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
                .setInitialDelay(15L, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
