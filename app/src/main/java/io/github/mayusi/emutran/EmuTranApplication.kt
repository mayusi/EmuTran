package io.github.mayusi.emutran

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import io.github.mayusi.emutran.domain.update.UpdateCheckWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * App entry point. Hilt requires a custom [Application] subclass.
 *
 * Shizuku-side note: the Shizuku 'provider' library auto-discovers the
 * binder via our <provider> tag in AndroidManifest.xml — no init code
 * needed here. Just having the dependency on classpath + the provider
 * declared is enough for Shizuku.pingBinder() / Shizuku.requestPermission()
 * to work.
 *
 * WorkManager note: we implement [Configuration.Provider] and supply
 * [HiltWorkerFactory] so that [@HiltWorker]-annotated workers (e.g.
 * [UpdateCheckWorker]) can receive their @Inject constructor dependencies
 * at runtime. WorkManager's default initialisation is disabled in the
 * manifest (tools:node="remove" on the WorkManagerInitializer) and
 * replaced by this custom configuration.
 *
 * On-demand init note: WorkManager.getInstance(context) lazily initialises
 * WorkManager using [workManagerConfiguration] the first time it is called.
 * We intentionally call it off the main thread (via Dispatchers.Default) to
 * avoid opening the WorkManager SQLite database on the critical-path UI
 * thread during cold start. The schedule() call is idempotent
 * (ExistingPeriodicWorkPolicy.KEEP) so there is no reliability risk in
 * deferring it by a few hundred milliseconds.
 */
@HiltAndroidApp
class EmuTranApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        // Schedule the periodic background update-check worker off the main
        // thread. WorkManager.getInstance() triggers lazy SQLite init which
        // can block for ~30 ms on cold start. The call is idempotent
        // (KEEP policy), so scheduling it slightly after process start has
        // no reliability impact.
        CoroutineScope(Dispatchers.Default).launch {
            UpdateCheckWorker.schedule(this@EmuTranApplication)
        }
    }
}
