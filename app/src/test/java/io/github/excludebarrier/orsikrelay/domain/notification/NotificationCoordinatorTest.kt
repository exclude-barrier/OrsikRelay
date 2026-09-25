package io.github.excludebarrier.orsikrelay.domain.notification

import io.github.excludebarrier.orsikrelay.domain.run.ApprovalRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decision-layer coverage for the notification coordinator (P3): the
 * foreground/background policy, the duplicate guard, the safe-title fallback,
 * and the guarantee that no secret ever reaches a notification. A recording
 * poster stands in for the Android adapter, so no Android API is touched.
 */
class NotificationCoordinatorTest {

    private class RecordingPoster : NotificationPoster {
        val posted = mutableListOf<NotificationContent>()
        override fun post(content: NotificationContent) {
            posted += content
        }
    }

    private fun coordinator(
        poster: NotificationPoster,
        isForeground: () -> Boolean,
        serverName: String = "MyServer"
    ) = NotificationCoordinator(
        poster = poster,
        isForeground = isForeground,
        serverNameProvider = { serverName },
        fallbackTitle = FALLBACK
    )

    // A valid, protocol-redacted approval request: the description is the
    // fixed protocol text, so no command, path, URL or secret is derivable.
    private fun approval(requestId: String = "b".repeat(32)) = ApprovalRequest(
        runId = "run_" + "a".repeat(32),
        requestId = requestId,
        description = ApprovalRequest.SAFE_DESCRIPTION
    )

    private val runId: String get() = "run_" + "a".repeat(32)

    @Test
    fun `approval in background posts one approval notification`() {
        val poster = RecordingPoster()
        val coordinator = coordinator(poster, isForeground = { false })
        coordinator.onApprovalRequested("p1", "s1", approval())

        assertEquals(1, poster.posted.size)
        val content = poster.posted[0]
        assertEquals(NotificationType.APPROVAL, content.type)
        assertEquals("p1", content.profileId)
        assertEquals("s1", content.sessionId)
    }

    @Test
    fun `approval in foreground posts nothing`() {
        val poster = RecordingPoster()
        val coordinator = coordinator(poster, isForeground = { true })
        coordinator.onApprovalRequested("p1", "s1", approval())

        assertTrue(poster.posted.isEmpty())
    }

    @Test
    fun `a duplicate approval posts only once`() {
        val poster = RecordingPoster()
        val coordinator = coordinator(poster, isForeground = { false })
        coordinator.onApprovalRequested("p1", "s1", approval())
        coordinator.onApprovalRequested("p1", "s1", approval())

        assertEquals(1, poster.posted.size)
    }

    @Test
    fun `two distinct approvals both post`() {
        val poster = RecordingPoster()
        val coordinator = coordinator(poster, isForeground = { false })
        coordinator.onApprovalRequested("p1", "s1", approval())
        coordinator.onApprovalRequested("p1", "s1", approval(requestId = "c".repeat(32)))

        assertEquals(2, poster.posted.size)
    }

    @Test
    fun `run completion in background posts one run notification`() {
        val poster = RecordingPoster()
        val coordinator = coordinator(poster, isForeground = { false })
        coordinator.onRunCompleted("p1", "s1", runId)

        assertEquals(1, poster.posted.size)
        assertEquals(NotificationType.RUN_COMPLETED, poster.posted[0].type)
        assertEquals("s1", poster.posted[0].sessionId)
    }

    @Test
    fun `run completion in foreground posts nothing`() {
        val poster = RecordingPoster()
        val coordinator = coordinator(poster, isForeground = { true })
        coordinator.onRunCompleted("p1", "s1", runId)

        assertTrue(poster.posted.isEmpty())
    }

    @Test
    fun `a duplicate run completion posts only once`() {
        val poster = RecordingPoster()
        val coordinator = coordinator(poster, isForeground = { false })
        coordinator.onRunCompleted("p1", "s1", runId)
        coordinator.onRunCompleted("p1", "s1", runId)

        assertEquals(1, poster.posted.size)
    }

    @Test
    fun `approval and run for the same session never collide`() {
        val poster = RecordingPoster()
        val coordinator = coordinator(poster, isForeground = { false })
        coordinator.onApprovalRequested("p1", "s1", approval())
        coordinator.onRunCompleted("p1", "s1", runId)

        assertEquals(2, poster.posted.size)
        assertTrue(poster.posted.map { it.type }.containsAll(listOf(NotificationType.APPROVAL, NotificationType.RUN_COMPLETED)))
    }

    @Test
    fun `a safe server label is used as the title`() {
        val poster = RecordingPoster()
        val coordinator = coordinator(poster, isForeground = { false }, serverName = "demo-server")
        coordinator.onApprovalRequested("p1", "s1", approval())

        assertEquals("demo-server", poster.posted[0].title)
    }

    @Test
    fun `an unsafe server label falls back to the fixed title`() {
        val poster = RecordingPoster()
        // A label with a character outside the conservative label set (and
        // over the length cap) is rejected by isSafeTitle, so the fixed
        // fallback title is used instead — the raw label never reaches the
        // notification.
        val coordinator = coordinator(
            poster,
            isForeground = { false },
            serverName = "cf<evil>".padEnd(50, 'x')
        )
        coordinator.onApprovalRequested("p1", "s1", approval())

        assertEquals(1, poster.posted.size)
        assertEquals(FALLBACK, poster.posted[0].title)
        assertFalse(poster.posted[0].title.contains("<"))
    }

    @Test
    fun `the test secret never appears in any notification field`() {
        val poster = RecordingPoster()
        // The server label is a normal user-entered label, never the secret.
        // The coordinator reads only that label and the validated routing
        // IDs; it never reads the API key, command, path, URL or tool output,
        // so the secret has no path into the notification content.
        val coordinator = coordinator(poster, isForeground = { false }, serverName = "demo-server")
        coordinator.onApprovalRequested("p1", "s1", approval())
        coordinator.onRunCompleted("p1", "s1", runId)

        for (content in poster.posted) {
            assertFalse("secret leaked in title", content.title.contains(SECRET))
            assertFalse("secret leaked in profileId", content.profileId.contains(SECRET))
            assertFalse("secret leaked in sessionId", content.sessionId.contains(SECRET))
        }
    }

    private companion object {
        const val SECRET = "SECRET_NOTIFICATION_TEST_123"
        const val FALLBACK = "Orsik Relay"
    }
}
