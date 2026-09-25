package io.github.excludebarrier.orsikrelay.domain.notification

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure decision coverage for the notification policy (P3): a notification is
 * posted exactly when the app is in the background AND the terminal event has
 * not already been notified. No Android, lifecycle or stream machinery is
 * involved.
 */
class NotificationPolicyTest {

    @Test
    fun `notifies only when background and not yet notified`() {
        assertTrue(
            NotificationPolicy.shouldNotify(isForeground = false, alreadyNotified = false)
        )
    }

    @Test
    fun `never notifies while the app is in the foreground`() {
        assertFalse(NotificationPolicy.shouldNotify(isForeground = true, alreadyNotified = false))
        assertFalse(NotificationPolicy.shouldNotify(isForeground = true, alreadyNotified = true))
    }

    @Test
    fun `never re-notifies an already notified terminal event`() {
        assertFalse(NotificationPolicy.shouldNotify(isForeground = false, alreadyNotified = true))
    }
}
