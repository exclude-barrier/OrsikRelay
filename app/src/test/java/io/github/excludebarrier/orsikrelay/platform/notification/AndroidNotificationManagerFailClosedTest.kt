package io.github.excludebarrier.orsikrelay.platform.notification

import io.github.excludebarrier.orsikrelay.domain.notification.NotificationContent
import io.github.excludebarrier.orsikrelay.domain.notification.NotificationType
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Deterministic JVM coverage of the adapter's fail-closed no-op path:
 * when the enabled gate reports false (the Android 13+
 * `POST_NOTIFICATIONS` runtime permission denied, or the app-level
 * notification switch off), a post must neither resolve the context nor touch
 * the Android notification machinery — the app stays fully functional without
 * notifications. The real granted path (channel, visibility, PendingIntent)
 * is verified by the instrumented adapter test against the system
 * NotificationManager.
 */
class AndroidNotificationManagerFailClosedTest {

    @Test
    fun notificationsDisabled_postsNothingAndDoesNotTouchFramework() {
        var contextTouches = 0
        val manager = AndroidNotificationManager(
            contextProvider = {
                contextTouches++
                throw AssertionError(
                    "the disabled gate must not resolve the context or post"
                )
            },
            notificationsEnabled = { false },
        )

        // Both notification types must be a clean no-op when disabled.
        manager.post(
            NotificationContent(
                type = NotificationType.APPROVAL,
                title = "Orsik Relay",
                profileId = "profile-1",
                sessionId = "session-1",
            )
        )
        manager.post(
            NotificationContent(
                type = NotificationType.RUN_COMPLETED,
                title = "Orsik Relay",
                profileId = "profile-1",
                sessionId = "session-1",
            )
        )

        assertFalse(
            "the disabled gate must not resolve the context (touches=$contextTouches)",
            contextTouches > 0,
        )
    }
}
