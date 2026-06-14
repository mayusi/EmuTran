package io.github.mayusi.emutran.domain.install

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Suspending install via Android's PackageInstaller.Session API.
 *
 * Why this and not ACTION_VIEW? With ACTION_VIEW you fire the intent
 * and have no idea when the user finishes — the install dialog, the
 * "App installed → Open / Done" confirmation, none of it tells you
 * anything. Stacking calls = race condition where the user accidentally
 * taps "Open" on app #1 and the rest of the queue silently dies.
 *
 * PackageInstaller.Session lets us:
 *   1. Write the APK bytes into a system-owned session.
 *   2. commit() with an IntentSender that gets a STATUS_* result.
 *   3. Receive the result via a one-shot BroadcastReceiver.
 *   4. Resume the suspended coroutine with success/failure.
 *
 * The loop therefore awaits each install before starting the next,
 * giving the user a clean one-at-a-time experience.
 *
 * Side note: this still shows a system install dialog (we don't have
 * silent install — that's Shizuku territory in Week 4). The win is
 * pure serialization: you tap Install, the dialog completes, then
 * the NEXT dialog appears. No queue races.
 */
@Singleton
class PackageInstallerInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
) : Installer {

    private val sessionCounter = AtomicInteger(0)

    /**
     * Whether the OS currently allows this app to install other APKs.
     * Required regardless of which install path we use.
     */
    fun canRequestInstalls(): Boolean =
        context.packageManager.canRequestPackageInstalls()

    fun openManageUnknownAppsSettings() {
        val intent = Intent(
            android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            android.net.Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /**
     * Install [apk] and suspend until the user completes the system
     * install dialog (or cancels). Returns one of [InstallResult].
     */
    override suspend fun install(apk: File): InstallResult {
        val pi = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        )
        val sessionId = pi.createSession(params)
        val session = pi.openSession(sessionId)
        try {
            // Stream the APK into the session.
            session.openWrite("apk", 0, apk.length()).use { out ->
                apk.inputStream().use { input -> input.copyTo(out) }
                session.fsync(out)
            }
        } catch (t: Throwable) {
            session.abandon()
            session.close()
            return InstallResult.Failed(t.message ?: t.javaClass.simpleName)
        }

        // Build a per-call broadcast action so multiple installs don't
        // crosstalk. Counter + timestamp + uid keeps it unique.
        val action = "${context.packageName}.INSTALL_CALLBACK." +
            "${sessionCounter.incrementAndGet()}.${System.nanoTime()}"

        val result = suspendCancellableCoroutine<InstallResult> { cont ->
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    if (intent.action != action) return
                    if (!cont.isActive) return

                    val status = intent.getIntExtra(
                        PackageInstaller.EXTRA_STATUS,
                        PackageInstaller.STATUS_FAILURE,
                    )
                    val message = intent.getStringExtra(
                        PackageInstaller.EXTRA_STATUS_MESSAGE,
                    )

                    if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                        // The system needs the user's permission for this
                        // specific install. Launch the confirm intent.
                        val confirmIntent = if (android.os.Build.VERSION.SDK_INT >= 33) {
                            intent.getParcelableExtra(
                                Intent.EXTRA_INTENT,
                                Intent::class.java,
                            )
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                        }
                        if (confirmIntent != null) {
                            confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            try {
                                ctx.startActivity(confirmIntent)
                            } catch (t: Throwable) {
                                runCatching { ctx.unregisterReceiver(this) }
                                cont.resume(InstallResult.Failed("Could not start confirm intent: ${t.message}"))
                            }
                            // Don't resume yet — a follow-up broadcast will
                            // arrive with the actual STATUS_SUCCESS / FAILURE
                            // once the user resolves the dialog.
                            return
                        } else {
                            runCatching { ctx.unregisterReceiver(this) }
                            cont.resume(InstallResult.Failed("Missing confirm intent"))
                            return
                        }
                    }

                    // Terminal status — unregister and resume.
                    runCatching { ctx.unregisterReceiver(this) }
                    val r: InstallResult = when (status) {
                        PackageInstaller.STATUS_SUCCESS -> InstallResult.Installed
                        PackageInstaller.STATUS_FAILURE_ABORTED ->
                            InstallResult.Cancelled
                        else -> InstallResult.Failed(
                            message?.takeIf { it.isNotBlank() }
                                ?: "Install failed (status $status)"
                        )
                    }
                    cont.resume(r)
                }
            }

            // RECEIVER_NOT_EXPORTED is the safer choice on API 33+ since
            // the broadcast only needs to reach us.
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(action),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )

            cont.invokeOnCancellation {
                runCatching { context.unregisterReceiver(receiver) }
                runCatching { session.abandon() }
            }

            // Build a PendingIntent the system can fire back at our receiver.
            val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_MUTABLE
            val pendingIntent = PendingIntent.getBroadcast(
                context, sessionId, Intent(action).setPackage(context.packageName),
                pendingFlags,
            )

            try {
                session.commit(pendingIntent.intentSender)
            } catch (t: Throwable) {
                runCatching { context.unregisterReceiver(receiver) }
                cont.resume(InstallResult.Failed("commit() failed: ${t.message}"))
            } finally {
                session.close()
            }
        }

        return result
    }
}
