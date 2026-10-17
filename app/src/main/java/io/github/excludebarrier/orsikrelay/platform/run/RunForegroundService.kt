package io.github.excludebarrier.orsikrelay.platform.run

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import io.github.excludebarrier.orsikrelay.R
import io.github.excludebarrier.orsikrelay.platform.locale.AppLocale
import io.github.excludebarrier.orsikrelay.platform.notification.NotificationSessionIntent

/**
 * The single run foreground service (background continuity).
 *
 * Started by the app-level run controller while the user's send action
 * still runs in the foreground, and stopped as soon as the run is
 * terminal. Its only job is to keep the app process out of the cached
 * state so the Android 14+ cached-app freezer cannot terminate the run's
 * SSE sockets; it holds NO chat state, no stream and no second state
 * machine.
 *
 * Type: `dataSync` — the run is a user-initiated, continuous data
 * exchange between the device and the Hermes server (verified against the
 * official foreground-service-type documentation; `remoteMessaging` is
 * for message continuity across device switches and does not describe
 * this app). No runtime permission is required for `dataSync`.
 *
 * Android 15+ places a 6-hour background limit on `dataSync` foreground
 * services: on [onTimeout] the service stops itself cleanly. Agent runs
 * are minutes long, so this only bounds a pathological stuck run; the
 * stream itself still ends with its terminal event.
 *
 * The notification is deliberately neutral: a fixed title and body, no
 * run content, no tool names, no paths, no approval actions (the
 * approval keeps its own separate notification). Tapping it opens the
 * session of the active run through the same intent contract as the
 * session notifications.
 */
class RunForegroundService : Service() {

    /**
     * The foreground notification is rendered from the persisted app
     * language on API < 33; API 33+ localizes the service through
     * LocaleManager.
     */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val profileId = intent?.getStringExtra(EXTRA_PROFILE_ID).orEmpty()
        val sessionId = intent?.getStringExtra(EXTRA_SESSION_ID).orEmpty()
        val notification = buildNotification(profileId, sessionId)
        try {
            startForeground(NOTIFICATION_ID, notification)
        } catch (error: Exception) {
            // A background-start denial or a missing notification channel
            // must never crash the process mid-run: degrade to a plain
            // background service; the run itself is unaffected.
            stopSelf()
        }
        // Never sticky: a process death is a run interruption on the
        // server side (the SSE disconnect) and must not resurrect the
        // service into a run-less foreground state.
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, type: Int) {
        // Android 15+ dataSync 6-hour background limit: stop cleanly.
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(profileId: String, sessionId: String): Notification {
        ensureChannel()
        val contentIntent =
            if (profileId.isNotEmpty() && sessionId.isNotEmpty()) {
                NotificationSessionIntent.buildIntent(this, profileId, sessionId)
            } else {
                Intent(this, io.github.excludebarrier.orsikrelay.MainActivity::class.java)
            }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            contentIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_run_title))
            .setContentText(getString(R.string.notification_run_body))
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setAutoCancel(false)
            .build()
    }

    private fun ensureChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val existing = manager.getNotificationChannel(CHANNEL_ID)
        if (existing == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_run_channel_name),
                // Low but allowed: the foreground notification must not
                // be silent-critical, but it must not alert either.
                NotificationManager.IMPORTANCE_LOW
            )
            channel.setShowBadge(false)
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val EXTRA_PROFILE_ID = "io.github.excludebarrier.orsikrelay.runservice.EXTRA_PROFILE_ID"
        const val EXTRA_SESSION_ID = "io.github.excludebarrier.orsikrelay.runservice.EXTRA_SESSION_ID"

        // Fixed ID, far from the small IDs the session-notification hash
        // produces in practice; the run controller stops this service
        // before any terminal notification is posted, so the two can never
        // visibly replace each other.
        private const val NOTIFICATION_ID = 0x0F3C
        private const val CHANNEL_ID = "orsik_relay_run"
    }
}
