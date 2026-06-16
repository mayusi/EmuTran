package io.github.mayusi.emutran.domain.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.mayusi.emutran.MainActivity
import io.github.mayusi.emutran.R
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Posts a system notification when new emulator updates are found by
 * [UpdateCheckWorker]. The notification is posted on the "emutran_updates"
 * channel (IMPORTANCE_DEFAULT), separate from the foreground-service
 * channel used by [SetupForegroundService] (IMPORTANCE_LOW).
 *
 * == Spam prevention ==
 *
 * The caller is responsible for signature-based deduplication (see
 * [UpdateStateStore.getLastNotifiedSignature]). This class does not
 * deduplicate — it simply posts whenever asked. The notification uses a
 * fixed [NOTIFICATION_ID], so re-posting for a genuinely different set
 * of updates replaces the previous notification rather than stacking.
 *
 * == Runtime permission (Android 13+) ==
 *
 * POST_NOTIFICATIONS is a runtime permission on API 33+. The app already
 * declares it in the manifest and requests it in the permission flow, but
 * the worker runs in the background where we can't prompt. If the permission
 * is not granted we silently return — the in-app badge still works.
 *
 * == Channel creation ==
 *
 * The channel is created lazily on the first call to [notifyUpdatesAvailable],
 * guarded by a channel-existence check (mirrors the pattern in
 * [SetupForegroundService.ensureNotificationChannel]).
 */
@Singleton
class UpdateNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Post (or replace) a notification summarising [count] pending updates.
     * [sampleNames] are shown in the body text; only the first few are
     * displayed before a "+N more" suffix.
     *
     * Does nothing if POST_NOTIFICATIONS is not granted on API 33+.
     */
    // canPostNotifications() does the actual POST_NOTIFICATIONS checkSelfPermission
    // guard for API 33+; lint can't trace the check through the helper, so suppress.
    @android.annotation.SuppressLint("MissingPermission")
    fun notifyUpdatesAvailable(count: Int, sampleNames: List<String>) {
        if (count <= 0) return
        if (!canPostNotifications()) return

        ensureChannel()

        val title = if (count == 1) {
            "1 emulator update available"
        } else {
            "$count emulator updates available"
        }

        val body = buildBodyText(count, sampleNames)

        // Tapping the notification opens MainActivity (the app's single activity).
        // FLAG_IMMUTABLE is required on API 31+ and is fine on older versions.
        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            REQUEST_CODE,
            tapIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)          // dismiss on tap
            .setOnlyAlertOnce(false)      // alert for genuinely new update sets
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    // ── Private helpers ────────────────────────────────────────────────────

    /**
     * Returns true when we're allowed to post notifications.
     *
     * On API 33+ we must hold POST_NOTIFICATIONS; on older APIs the
     * manifest-declared permission is sufficient (no runtime check needed).
     * We also check [NotificationManagerCompat.areNotificationsEnabled] which
     * covers the case where the user disabled notifications at the app level
     * in system settings.
     */
    private fun canPostNotifications(): Boolean {
        // If the user turned off all notifications for the app in system settings.
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false

        // API 33+ requires the runtime POST_NOTIFICATIONS permission.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return false
        }

        return true
    }

    /**
     * Lazily creates the notification channel if it doesn't exist yet.
     * Idempotent — calling this multiple times is safe (the system ignores
     * duplicate channel creations with the same id).
     *
     * IMPORTANCE_DEFAULT = makes a sound and shows in the shade; appropriate
     * for "here's useful information" rather than "action required urgently".
     */
    private fun ensureChannel() {
        val manager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // Early exit if already registered to avoid repeated IPC on every check.
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Emulator updates",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Notifies when newer versions of installed emulators are available."
        }
        manager.createNotificationChannel(channel)
    }

    /**
     * Builds a concise body string like:
     *   "Dolphin, PPSSPP, +3 more"       (many updates)
     *   "Dolphin, PPSSPP, RetroArch"     (few updates, all fit)
     *   "Dolphin"                         (single update)
     */
    private fun buildBodyText(count: Int, sampleNames: List<String>): String {
        val maxShown = 3
        return if (sampleNames.size <= maxShown) {
            sampleNames.joinToString(", ")
        } else {
            val shown = sampleNames.take(maxShown).joinToString(", ")
            val remaining = count - maxShown
            "$shown, +$remaining more"
        }
    }

    companion object {
        /** Channel id for emulator-update notifications. */
        const val CHANNEL_ID = "emutran_updates"

        /**
         * Fixed notification id — re-posting for a different update set
         * replaces rather than stacks. Deliberately different from
         * [SetupForegroundService.NOTIFICATION_ID] (1001).
         */
        private const val NOTIFICATION_ID = 1002

        /** PendingIntent request code (must be unique within the app). */
        private const val REQUEST_CODE = 2001
    }
}
