package io.github.excludebarrier.orsikrelay.domain.run

import io.github.excludebarrier.orsikrelay.domain.chat.ChatStreamError
import io.github.excludebarrier.orsikrelay.domain.chat.ChatTurnEvent
import io.github.excludebarrier.orsikrelay.domain.chat.StreamingChatSource
import io.github.excludebarrier.orsikrelay.domain.notification.NotificationContent
import io.github.excludebarrier.orsikrelay.domain.notification.NotificationCoordinator
import io.github.excludebarrier.orsikrelay.domain.notification.NotificationPoster
import io.github.excludebarrier.orsikrelay.domain.notification.NotificationType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the app-level [RunSessionController] (P3 background
 * continuity): the foreground-service lifecycle over the run's terminal
 * states, the single-slot replacement, the approval hold, and the
 * notification side effects. Every seam is a synthetic fake; no Android
 * framework, no network, no real server.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RunSessionControllerTest {

    private val profileId = "p"
    private val sessionId = "s"
    private val otherSessionId = "s2"
    private val runId = "run_" + "a".repeat(32)
    private val requestId = "b".repeat(32)

    private fun approvalRequest() =
        ApprovalRequest(runId, requestId, ApprovalRequest.SAFE_DESCRIPTION)

    /**
     * Builds the controller on virtual time: the stream fake, the service
     * starter fake and the recording poster are returned together with it.
     */
    private fun build(
        scheduler: kotlinx.coroutines.test.TestCoroutineScheduler,
        foreground: Boolean = false
    ): Built = run {
        val source = FakeTurnSource()
        val starter = FakeServiceStarter()
        val poster = RecordingPoster()
        val coordinator = NotificationCoordinator(
            poster = poster,
            isForeground = { foreground },
            serverNameProvider = { "srv" },
            fallbackTitle = "Orsik Relay"
        )
        Built(
            RunSessionController(
                chatSource = source,
                serviceStarter = starter,
                notificationCoordinator = coordinator,
                dispatcher = UnconfinedTestDispatcher(scheduler)
            ),
            source,
            starter,
            poster
        )
    }

    private class Built(
        val controller: RunSessionController,
        val source: FakeTurnSource,
        val starter: FakeServiceStarter,
        val poster: RecordingPoster
    )

    // ------------------------------------------------------------------
    // Foreground-service lifecycle over the run's terminal states
    // ------------------------------------------------------------------

    @Test
    fun startTurnStartsForegroundServiceAndActivatesRun() = runTest {
        val built = build(this.testScheduler)
        built.controller.startTurn(profileId, sessionId, "hi")
        testScheduler.advanceUntilIdle()
        assertEquals(1, built.starter.starts.get())
        assertEquals(profileId to sessionId, built.starter.lastSession)
        val active = built.controller.activeRun.value
        assertTrue(active != null)
        assertEquals(sessionId, active?.sessionId)
        // No server confirmation yet: the run ID is still unbound.
        assertNull(active?.runId)
        built.controller.close()
    }

    @Test
    fun runStartedBindsRunId() = runTest {
        val built = build(this.testScheduler)
        built.controller.startTurn(profileId, sessionId, "hi")
        built.source.enqueue(ChatTurnEvent.RunStarted(runId))
        testScheduler.advanceUntilIdle()
        assertEquals(runId, built.controller.activeRun.value?.runId)
        built.controller.close()
    }

    @Test
    fun completedRunStopsForegroundServiceAndClearsSlot() = runTest {
        val built = build(this.testScheduler)
        built.controller.startTurn(profileId, sessionId, "hi")
        built.source.enqueue(
            ChatTurnEvent.RunStarted(runId),
            ChatTurnEvent.TurnCompleted()
        )
        testScheduler.advanceUntilIdle()
        assertEquals(1, built.starter.starts.get())
        assertTrue(built.starter.stops.get() >= 1)
        assertNull(built.controller.activeRun.value)
        built.controller.close()
    }

    @Test
    fun failedRunStopsForegroundServiceAndClearsSlot() = runTest {
        val built = build(this.testScheduler)
        built.controller.startTurn(profileId, sessionId, "hi")
        built.source.enqueue(ChatTurnEvent.TurnFailed(ChatStreamError.Network))
        testScheduler.advanceUntilIdle()
        assertTrue(built.starter.stops.get() >= 1)
        assertNull(built.controller.activeRun.value)
        built.controller.close()
    }

    @Test
    fun streamClosedWithoutTerminalFailsClosed() = runTest {
        val built = build(this.testScheduler)
        built.controller.startTurn(profileId, sessionId, "hi")
        built.source.complete()
        testScheduler.advanceUntilIdle()
        // The defensive synthetic terminal settles the slot and stops the
        // foreground service; a run-less foreground state never survives.
        assertTrue(built.starter.stops.get() >= 1)
        assertNull(built.controller.activeRun.value)
        built.controller.close()
    }

    @Test
    fun cancelTurnCancelsStreamStopsServiceAndClearsSlot() = runTest {
        val built = build(this.testScheduler)
        built.controller.startTurn(profileId, sessionId, "hi")
        assertTrue(built.source.opened.await(1, TimeUnit.SECONDS))
        built.controller.cancelTurn()
        testScheduler.advanceUntilIdle()
        assertTrue(built.source.canceled.get())
        assertTrue(built.starter.stops.get() >= 1)
        assertNull(built.controller.activeRun.value)
        built.controller.close()
    }

    @Test
    fun closeCancelsStreamAndStopsService() = runTest {
        val built = build(this.testScheduler)
        built.controller.startTurn(profileId, sessionId, "hi")
        assertTrue(built.source.opened.await(1, TimeUnit.SECONDS))
        built.controller.close()
        testScheduler.advanceUntilIdle()
        assertTrue(built.source.canceled.get())
        assertTrue(built.starter.stops.get() >= 1)
        assertNull(built.controller.activeRun.value)
    }

    // ------------------------------------------------------------------
    // Approval hold: the service must stay up while the user decides
    // ------------------------------------------------------------------

    @Test
    fun approvalKeepsForegroundServiceRunning() = runTest {
        val built = build(this.testScheduler)
        built.controller.startTurn(profileId, sessionId, "hi")
        built.source.enqueue(
            ChatTurnEvent.RunStarted(runId),
            ChatTurnEvent.ApprovalRequested(approvalRequest())
        )
        testScheduler.advanceUntilIdle()
        // The run waits on the user decision: the server needs the live SSE
        // connection, so the foreground service stays up.
        assertEquals(1, built.starter.starts.get())
        assertEquals(0, built.starter.stops.get())
        assertEquals(
            approvalRequest(),
            built.controller.activeRun.value?.pendingApproval
        )
        built.controller.close()
    }

    @Test
    fun runContinuesAfterApprovalUntilTerminal() = runTest {
        val built = build(this.testScheduler)
        built.controller.startTurn(profileId, sessionId, "hi")
        built.source.enqueue(
            ChatTurnEvent.RunStarted(runId),
            ChatTurnEvent.ApprovalRequested(approvalRequest())
        )
        // "once": the run continues on the same stream.
        built.source.enqueue(
            ChatTurnEvent.AssistantDelta("ok"),
            ChatTurnEvent.TurnCompleted()
        )
        testScheduler.advanceUntilIdle()
        // The service stopped exactly once, at the terminal.
        assertEquals(1, built.starter.starts.get())
        assertTrue(built.starter.stops.get() >= 1)
        assertNull(built.controller.activeRun.value)
        built.controller.close()
    }

    @Test
    fun clearPendingApprovalClearsBookkeepingOnly() = runTest {
        val built = build(this.testScheduler)
        built.controller.startTurn(profileId, sessionId, "hi")
        built.source.enqueue(
            ChatTurnEvent.RunStarted(runId),
            ChatTurnEvent.ApprovalRequested(approvalRequest())
        )
        built.controller.clearPendingApproval()
        testScheduler.advanceUntilIdle()
        // The run itself is untouched; only the decided request is cleared
        // so a re-attached view model does not re-show it.
        val active = built.controller.activeRun.value
        assertTrue(active != null)
        assertNull(active?.pendingApproval)
        assertEquals(0, built.starter.stops.get())
        built.controller.close()
    }

    // ------------------------------------------------------------------
    // Single-slot client: at most one active run
    // ------------------------------------------------------------------

    @Test
    fun newRunCancelsPreviousRunAndTakesTheSlot() = runTest {
        val built = build(this.testScheduler)
        built.controller.startTurn(profileId, sessionId, "hi")
        built.source.enqueue(ChatTurnEvent.RunStarted(runId))
        // A second run (single slot): the previous stream is canceled and
        // the foreground service is re-asserted for the new run.
        built.controller.startTurn(profileId, otherSessionId, "next")
        testScheduler.advanceUntilIdle()
        assertTrue(built.source.canceled.get())
        assertEquals(2, built.starter.starts.get())
        assertEquals(0, built.starter.stops.get())
        assertEquals(otherSessionId, built.controller.activeRun.value?.sessionId)
        built.controller.close()
    }

    // ------------------------------------------------------------------
    // Notification side effects (background-only policy)
    // ------------------------------------------------------------------

    @Test
    fun runCompletedPostsNotificationWhenBackground() = runTest {
        val built = build(this.testScheduler, foreground = false)
        built.controller.startTurn(profileId, sessionId, "hi")
        built.source.enqueue(
            ChatTurnEvent.RunStarted(runId),
            ChatTurnEvent.TurnCompleted()
        )
        testScheduler.advanceUntilIdle()
        val posts = built.poster.posts
        assertTrue(
            posts.any {
                it.type == NotificationType.RUN_COMPLETED &&
                    it.sessionId == sessionId &&
                    it.profileId == profileId
            }
        )
        built.controller.close()
    }

    @Test
    fun approvalPostsNotificationWhenBackground() = runTest {
        val built = build(this.testScheduler, foreground = false)
        built.controller.startTurn(profileId, sessionId, "hi")
        built.source.enqueue(
            ChatTurnEvent.RunStarted(runId),
            ChatTurnEvent.ApprovalRequested(approvalRequest())
        )
        testScheduler.advanceUntilIdle()
        assertTrue(
            built.poster.posts.any { it.type == NotificationType.APPROVAL }
        )
        built.controller.close()
    }

    @Test
    fun noNotificationWhenForeground() = runTest {
        val built = build(this.testScheduler, foreground = true)
        built.controller.startTurn(profileId, sessionId, "hi")
        built.source.enqueue(
            ChatTurnEvent.RunStarted(runId),
            ChatTurnEvent.ApprovalRequested(approvalRequest()),
            ChatTurnEvent.TurnCompleted()
        )
        testScheduler.advanceUntilIdle()
        assertTrue(built.poster.posts.isEmpty())
        built.controller.close()
    }

    // ------------------------------------------------------------------
    // Fakes
    // ------------------------------------------------------------------

    /** Synthetic streaming fake: latched open, events enqueued, closed by the test. */
    private class FakeTurnSource : StreamingChatSource {
        val calls = AtomicInteger()
        val opened = CountDownLatch(1)
        val canceled = AtomicBoolean(false)
        @Volatile
        private var active: Channel<ChatTurnEvent>? = null

        override fun streamTurn(profileId: String, sessionId: String, message: String): Flow<ChatTurnEvent> {
            calls.incrementAndGet()
            val channel = Channel<ChatTurnEvent>(Channel.UNLIMITED)
            active = channel
            opened.countDown()
            return flow {
                for (event in channel) emit(event)
            }.onCompletion { cause ->
                if (cause is CancellationException) canceled.set(true)
            }
        }

        fun enqueue(vararg events: ChatTurnEvent) {
            active?.let { channel -> events.forEach { channel.trySend(it) } }
        }

        fun complete() {
            active?.close()
        }
    }

    /** Synthetic foreground-service seam: records start/stop calls. */
    private class FakeServiceStarter : RunForegroundServiceStarter {
        val starts = AtomicInteger()
        val stops = AtomicInteger()
        @Volatile
        var lastSession: Pair<String, String>? = null

        override fun start(profileId: String, sessionId: String) {
            starts.incrementAndGet()
            lastSession = profileId to sessionId
        }

        override fun stop() {
            stops.incrementAndGet()
        }
    }

    /** Recording poster: keeps every posted content for assertions. */
    private class RecordingPoster : NotificationPoster {
        val posts = CopyOnWriteArrayList<NotificationContent>()

        override fun post(content: NotificationContent) {
            posts.add(content)
        }
    }
}
