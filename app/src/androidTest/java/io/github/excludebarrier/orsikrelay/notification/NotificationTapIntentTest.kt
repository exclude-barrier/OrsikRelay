package io.github.excludebarrier.orsikrelay.notification

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import io.github.excludebarrier.orsikrelay.MainActivity
import io.github.excludebarrier.orsikrelay.platform.notification.NotificationSessionIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * MainActivity is exported, so a notification-tap intent must prove it was
 * built by this app: only the per-install tap token makes it open a chat.
 */
class NotificationTapIntentTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun ownIntentOpensTheChat() {
        val intent = NotificationSessionIntent.buildIntent(context, "profile_1", "session_1")
        assertEquals(
            "profile_1" to "session_1",
            NotificationSessionIntent.parse(intent, NotificationSessionIntent.tapToken(context))
        )
    }

    @Test
    fun forgedIntentWithoutOrWithAWrongTokenIsIgnored() {
        val token = NotificationSessionIntent.tapToken(context)
        val forged = Intent(context, MainActivity::class.java).apply {
            action = NotificationSessionIntent.ACTION_OPEN_SESSION
            putExtra(NotificationSessionIntent.EXTRA_PROFILE_ID, "profile_1")
            putExtra(NotificationSessionIntent.EXTRA_SESSION_ID, "session_1")
        }
        assertNull(NotificationSessionIntent.parse(forged, token))
        forged.putExtra(NotificationSessionIntent.EXTRA_TAP_TOKEN, "0".repeat(32))
        assertNull(NotificationSessionIntent.parse(forged, token))
    }

    @Test
    fun tokenIsStableAcrossCalls() {
        assertEquals(
            NotificationSessionIntent.tapToken(context),
            NotificationSessionIntent.tapToken(context)
        )
    }
}
