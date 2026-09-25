package io.github.excludebarrier.orsikrelay.domain.notification

/**
 * Abstraction the domain [NotificationCoordinator] uses to raise a local
 * notification (P3). The production implementation is the Android adapter
 * [io.github.excludebarrier.orsikrelay.platform.notification.AndroidNotificationManager];
 * tests substitute a recording fake. Keeping this seam here means the domain
 * never touches an Android notification API.
 *
 * Implementations MUST be safe to call when notifications are disabled or
 * the runtime permission is denied: they must simply no-op (never throw and
 * never retry), so a denied permission can never spam or crash the app.
 */
fun interface NotificationPoster {
    fun post(content: NotificationContent)
}
