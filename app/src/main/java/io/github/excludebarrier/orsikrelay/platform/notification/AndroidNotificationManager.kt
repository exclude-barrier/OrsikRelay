package io.github.excludebarrier.orsikrelay.platform.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import io.github.excludebarrier.orsikrelay.R
import io.github.excludebarrier.orsikrelay.domain.notification.NotificationContent
import io.github.excludebarrier.orsikrelay.domain.notification.NotificationPoster
import io.github.excludebarrier.orsikrelay.domain.notification.NotificationType

/**
 * The single Android adapter that raises local notifications (P3). It is the
 * ONLY place in the app that touches an Android notification API; the domain
 * [NotificationCoordinator] only sees the [NotificationPoster] seam.
 *
 * Security / privacy:
 * - the content title/body come only from the closed [NotificationContent] and
 *   a localized resource chosen by [NotificationContent.type]; no wire text,
 *   command, path, URL or secret can reach the notification;
 * - the notification is [Notification.VISIBILITY_PRIVATE], so on a secure
 *   lockscreen only the app name and the generic label are shown, never the
 *   content;
 * - the content intent is a [PendingIntent.getActivity] with
 *   [PendingIntent.FLAG_IMMUTABLE] targeting the app's own MainActivity by an
 *   explicit component and only safe routing extras — an external component or
 *   a mutated intent is not possible;
 * - if notifications are disabled or the runtime permission is denied, every
 *   post is a no-op: nothing is retried, nothing is thrown, nothing spams.
 *
 * Testability: the [Context] is resolved through [contextProvider] and the
 * enabled state through [notificationsEnabled] so the fail-closed no-op path
 * can be exercised deterministically on the JVM without any Android framework
 * object. The production [constructor] wires both to the real system state.
 */
class AndroidNotificationManager(
    private val contextProvider: () -> Context,
    private val notificationsEnabled: () -> Boolean,
) : NotificationPoster {

    /**
     * Production constructor: the enabled gate reads the real
     * [NotificationManager.areNotificationsEnabled] state, which is false both
     * for an Android 13+ [android.Manifest.permission.POST_NOTIFICATIONS]
     * denial and for the app-level notification switch on every API level.
     */
    constructor(context: Context) : this(
        contextProvider = { context },
        notificationsEnabled = {
            requireNotNull(context.getSystemService(NotificationManager::class.java))
                .areNotificationsEnabled()
        },
    )

    private val notificationManager: NotificationManager
        get() = requireNotNull(
            contextProvider().getSystemService(NotificationManager::class.java)
        )

    override fun post(content: NotificationContent) {
        // Fail-closed, spam-free: no-op when the user has turned the app's
        // notifications off (covers both the Android 13+ runtime permission
        // denial and the app-level setting on every API level).
        if (!notificationsEnabled()) return
        ensureChannel()

        val context = contextProvider()
        val contentIntent = buildContentIntent(content)
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(content.title)
            .setContentText(content.bodyText(context))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setOngoing(false)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .build()

        notificationManager.notify(notificationId(content), notification)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                contextProvider().getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                // Local run/approval notices: present in the shade, no sound,
                // so a busy server never produces notification noise.
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildContentIntent(content: NotificationContent): PendingIntent {
        val context = contextProvider()
        val intent = NotificationSessionIntent.buildIntent(
            context,
            content.profileId,
            content.sessionId
        )
        return PendingIntent.getActivity(
            context,
            notificationId(content),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /**
     * A stable notification id per (session, type) so a new event for the same
     * session replaces the previous notice instead of stacking duplicates.
     */
    private fun notificationId(content: NotificationContent): Int {
        val base = content.profileId.hashCode() * 31 + content.sessionId.hashCode()
        return (base * 31 + content.type.ordinal) and 0x7fffffff
    }

    companion object {
        const val CHANNEL_ID = "orsik_relay_local"
    }
}

private fun NotificationContent.bodyText(context: Context): String =
    when (type) {
        NotificationType.APPROVAL ->
            context.getString(R.string.notification_approval_body)
        NotificationType.RUN_COMPLETED ->
            context.getString(R.string.notification_run_completed_body)
    }
