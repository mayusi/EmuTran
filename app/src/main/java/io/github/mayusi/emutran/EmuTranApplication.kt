package io.github.mayusi.emutran

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import io.github.mayusi.emutran.domain.update.UpdateCheckWorker
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
        // Schedule the periodic background update-check worker.
        // KEEP policy means this is a no-op if the work is already enqueued.
        UpdateCheckWorker.schedule(this)
    }
}
