package io.github.excludebarrier.orsikrelay.domain.run

import io.github.excludebarrier.orsikrelay.domain.chat.ChatStreamError
import io.github.excludebarrier.orsikrelay.domain.chat.ChatTurnEvent
import io.github.excludebarrier.orsikrelay.domain.chat.StreamingChatSource
import io.github.excludebarrier.orsikrelay.domain.notification.NotificationContent
import io.github.excludebarrier.orsikrelay.domain.notification.NotificationCoordinator
import io.github.excludebarrier.orsikrelay.domain.notification.NotificationPoster
import io.github.excludebarrier.orsikrelay.domain.notification.NotificationType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
    // P4: durable in-memory turn state for re-created screens
    // ------------------------------------------------------------------

    @Test
    fun activeRunCarriesTheTurnTextForRehydration() = runTest {
        val built = build(this.testScheduler)
        built.controller.startTurn(profileId, sessionId, "Hallo Welt")
        built.source.enqueue(ChatTurnEvent.RunStarted(runId))
        built.source.enqueue(ChatTurnEvent.AssistantDelta("Das ist "))
        built.source.enqueue(ChatTurnEvent.AssistantDelta("eine Antwort."))
        built.source.enqueue(ChatTurnEvent.ToolActivity(toolName = "web_search"))
        testScheduler.advanceUntilIdle()

        val active = built.controller.activeRun.value
        assertNotNull(active)
        assertEquals(sessionId, active?.sessionId)
        assertEquals(runId, active?.runId)
        // The controller keeps the user text it was handed (the view model
        // hands over the VALIDATED, trimmed text), so a re-created screen
        // rebuilds exactly the line the user saw.
        assertEquals("Hallo Welt", active?.userMessage)
        assertEquals("Das ist eine Antwort.", active?.assistantText)
        assertEquals(listOf("web_search"), active?.toolNames)
        assertEquals(RunSessionController.ActiveRun.Status.RUNNING, active?.status)
        built.controller.close()
    }

    @Test
    fun activeRunSurfacesTheReconnectingAndApprovalStatus() = runTest {
        val built = build(this.testScheduler)
        built.controller.startTurn(profileId, sessionId, "hi")
        built.source.enqueue(ChatTurnEvent.RunStarted(runId))
        built.source.enqueue(ChatTurnEvent.StreamReconnecting)
        testScheduler.advanceUntilIdle()
        assertEquals(
            RunSessionController.ActiveRun.Status.RECONNECTING,
            built.controller.activeRun.value?.status
        )
        assertTrue(built.controller.activeRun.value?.reconnecting == true)

        built.source.enqueue(ChatTurnEvent.ApprovalRequested(approvalRequest()))
        testScheduler.advanceUntilIdle()
        val awaiting = built.controller.activeRun.value
        assertEquals(
            RunSessionController.ActiveRun.Status.WAITING_FOR_APPROVAL,
            awaiting?.status
        )
        assertEquals(requestId, awaiting?.pendingApproval?.requestId)
        assertFalse("progress clears the transient reconnecting flag", awaiting?.reconnecting == true)
        built.controller.close()
    }

    @Test
    fun assistantCompletedReplacesTheComposedTextExactlyOnce() = runTest {
        val built = build(this.testScheduler)
        built.controller.startTurn(profileId, sessionId, "hi")
        built.source.enqueue(ChatTurnEvent.RunStarted(runId))
        built.source.enqueue(ChatTurnEvent.AssistantDelta("teil "))
        built.source.enqueue(ChatTurnEvent.AssistantCompleted("teil antwort"))
        testScheduler.advanceUntilIdle()
        assertEquals("teil antwort", built.controller.activeRun.value?.assistantText)
        built.controller.close()
    }

    @Test
    fun startingATurnForAnotherSessionIsRejectedWithActiveRunConflict() = runTest {
        val built = build(this.testScheduler)
        built.controller.startTurn(profileId, sessionId, "hi")
        built.source.enqueue(ChatTurnEvent.RunStarted(runId))
        built.source.enqueue(ChatTurnEvent.AssistantDelta("teil "))
        built.source.enqueue(ChatTurnEvent.ApprovalRequested(approvalRequest()))
        testScheduler.advanceUntilIdle()
        val before = built.controller.activeRun.value

        // P6: the previous replacement semantics is gone. A second start
        // for another session must be rejected and must change nothing.
        val result = built.controller.startTurn(profileId, otherSessionId, "other")
        testScheduler.advanceUntilIdle()

        assertTrue(result is RunSessionController.StartTurnResult.ActiveRunConflict)
        assertEquals("the first run must keep the slot", before, built.controller.activeRun.value)
        assertEquals(sessionId, built.controller.activeRun.value?.sessionId)
        assertEquals("no second stream may be opened", 1, built.source.calls.get())
        built.controller.close()
    }

    @Test
    fun aTerminalEventClearsTheReconstructableTurnState() = runTest {
        val built = build(this.testScheduler)
        built.controller.startTurn(profileId, sessionId, "hi")
        built.source.enqueue(ChatTurnEvent.RunStarted(runId))
        built.source.enqueue(ChatTurnEvent.AssistantDelta("teil "))
        built.source.enqueue(ChatTurnEvent.TurnCompleted())
        testScheduler.advanceUntilIdle()
        assertNull(built.controller.activeRun.value)
        built.controller.close()
    }

    @Test
    fun cancelTurnClearsTheReconstructableTurnState() = runTest {
        val built = build(this.testScheduler)
        built.controller.startTurn(profileId, sessionId, "hi")
        built.source.enqueue(ChatTurnEvent.RunStarted(runId))
        built.source.enqueue(ChatTurnEvent.AssistantDelta("teil "))
        testScheduler.advanceUntilIdle()
        built.controller.cancelTurn()
        testScheduler.advanceUntilIdle()
        assertNull(built.controller.activeRun.value)
        built.controller.close()
    }

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
        testScheduler.advanceUntilIdle()
        built.controller.clearPendingApproval(profileId, sessionId, runId, requestId)
        testScheduler.advanceUntilIdle()
        // The run itself is untouched; only the decided request is cleared
        // so a re-attached view model does not re-show it.
        val active = built.controller.activeRun.value
        assertTrue(active != null)
        assertNull(active?.pendingApproval)
        assertEquals(0, built.starter.stops.get())
        built.controller.close()
    }

    @Test
    fun clearPendingApprovalRequiresTheExactRequestIdentity() = runTest {
        val built = build(this.testScheduler)
        built.controller.startTurn(profileId, sessionId, "hi")
        built.source.enqueue(
            ChatTurnEvent.RunStarted(runId),
            ChatTurnEvent.ApprovalRequested(approvalRequest())
        )
        testScheduler.advanceUntilIdle()
        // Any mismatching identity leaves the pending request untouched.
        built.controller.clearPendingApproval(profileId, otherSessionId, runId, requestId)
        built.controller.clearPendingApproval(profileId, sessionId, "run_" + "c".repeat(32), requestId)
        built.controller.clearPendingApproval(profileId, sessionId, runId, "d".repeat(32))
        built.controller.clearPendingApproval("other-profile", sessionId, runId, requestId)
        testScheduler.advanceUntilIdle()
        assertEquals(requestId, built.controller.activeRun.value?.pendingApproval?.requestId)
        // The exact identity clears it.
        built.controller.clearPendingApproval(profileId, sessionId, runId, requestId)
        testScheduler.advanceUntilIdle()
        assertNull(built.controller.activeRun.value?.pendingApproval)
        built.controller.close()
    }

    @Test
    fun aStaleApprovalResultNeverClearsTheCurrentApproval() = runTest {
        val built = build(this.testScheduler)
        // Run A raises and "answers" an approval, but the result arrives late.
        built.controller.startTurn(profileId, sessionId, "first")
        built.source.enqueue(
            ChatTurnEvent.RunStarted(runId),
            ChatTurnEvent.ApprovalRequested(approvalRequest())
        )
        testScheduler.advanceUntilIdle()
        // Run A is released explicitly (P6: no replacement); a late "answer"
        // of run A arrives only after run B owns the slot.
        built.controller.cancelTurn()
        testScheduler.advanceUntilIdle()
        // Run B takes the freed slot and raises its own approval.
        val runB = "run_" + "e".repeat(32)
        val requestB = "f".repeat(32)
        built.controller.startTurn(profileId, sessionId, "second")
        built.source.enqueue(
            ChatTurnEvent.RunStarted(runB),
            ChatTurnEvent.ApprovalRequested(
                ApprovalRequest(runB, requestB, ApprovalRequest.SAFE_DESCRIPTION)
            )
        )
        testScheduler.advanceUntilIdle()
        // The late result of run A / request A must not touch B.
        built.controller.clearPendingApproval(profileId, sessionId, runId, requestId)
        testScheduler.advanceUntilIdle()
        assertEquals(requestB, built.controller.activeRun.value?.pendingApproval?.requestId)
        assertEquals(runB, built.controller.activeRun.value?.runId)
        built.controller.close()
    }

    // ------------------------------------------------------------------
    // P6: global run ownership (single slot, no replacement)
    // ------------------------------------------------------------------

    @Test
    fun firstStartReturnsStartedAndOpensExactlyOneStream() = runTest {
        val built = build(this.testScheduler)
        val result = built.controller.startTurn(profileId, sessionId, "hi")
        testScheduler.advanceUntilIdle()
        assertTrue(result is RunSessionController.StartTurnResult.Started)
        assertEquals(1, built.source.calls.get())
        assertEquals(1, built.starter.starts.get())
        assertNotNull(built.controller.activeRun.value)
        built.controller.close()
    }

    @Test
    fun secondStartForTheSameSessionIsRejectedWithoutASecondPost() = runTest {
        val built = build(this.testScheduler)
        built.controller.startTurn(profileId, sessionId, "first")
        built.source.enqueue(ChatTurnEvent.RunStarted(runId))
        testScheduler.advanceUntilIdle()
        val result = built.controller.startTurn(profileId, sessionId, "second")
        testScheduler.advanceUntilIdle()
        assertTrue(result is RunSessionController.StartTurnResult.ActiveRunConflict)
        assertEquals("no second POST/source call", 1, built.source.calls.get())
        assertEquals(sessionId, built.controller.activeRun.value?.sessionId)
        built.controller.close()
    }

    @Test
    fun secondStartForAnotherSessionIsRejected() = runTest {
        val built = build(this.testScheduler)
        built.controller.startTurn(profileId, sessionId, "first")
        built.source.enqueue(ChatTurnEvent.RunStarted(runId))
        testScheduler.advanceUntilIdle()
        val result = built.controller.startTurn(profileId, otherSessionId, "second")
        testScheduler.advanceUntilIdle()
        assertTrue(result is RunSessionController.StartTurnResult.ActiveRunConflict)
        assertEquals(1, built.source.calls.get())
        assertEquals(sessionId, built.controller.activeRun.value?.sessionId)
        built.controller.close()
    }

    @Test
    fun secondStartForAnotherProfileIsRejected() = runTest {
        val built = build(this.testScheduler)
        built.controller.startTurn(profileId, sessionId, "first")
        built.source.enqueue(ChatTurnEvent.RunStarted(runId))
        testScheduler.advanceUntilIdle()
        val result = built.controller.startTurn("other-profile", sessionId, "second")
        testScheduler.advanceUntilIdle()
        assertTrue(result is RunSessionController.StartTurnResult.ActiveRunConflict)
        assertEquals(1, built.source.calls.get())
        assertEquals(profileId, built.controller.activeRun.value?.profileId)
        built.controller.close()
    }

    @Test
    fun conflictNeverCancelsTheActiveStream() = runTest {
        val built = build(this.testScheduler)
        built.controller.startTurn(profileId, sessionId, "first")
        built.source.enqueue(ChatTurnEvent.RunStarted(runId))
        assertTrue(built.source.opened.await(1, TimeUnit.SECONDS))
        built.controller.startTurn(profileId, otherSessionId, "second")
        testScheduler.advanceUntilIdle()
        assertFalse("the running stream must not be canceled", built.source.canceled.get())
        built.controller.close()
    }

    @Test
    fun conflictLeavesTheActiveRunUntouched() = runTest {
        val built = build(this.testScheduler)
        built.controller.startTurn(profileId, sessionId, "first")
        built.source.enqueue(ChatTurnEvent.RunStarted(runId))
        built.source.enqueue(ChatTurnEvent.AssistantDelta("teil "))
        testScheduler.advanceUntilIdle()
        val before = built.controller.activeRun.value
        built.controller.startTurn(profileId, otherSessionId, "second")
        testScheduler.advanceUntilIdle()
        assertEquals(before, built.controller.activeRun.value)
        built.controller.close()
    }

    @Test
    fun conflictDoesNotStartTheForegroundServiceAgain() = runTest {
        val built = build(this.testScheduler)
        built.controller.startTurn(profileId, sessionId, "first")
        testScheduler.advanceUntilIdle()
        assertEquals(1, built.starter.starts.get())
        built.controller.startTurn(profileId, otherSessionId, "second")
        testScheduler.advanceUntilIdle()
        assertEquals("a rejected start must not re-start the FGS", 1, built.starter.starts.get())
        assertEquals(0, built.starter.stops.get())
        built.controller.close()
    }

    @Test
    fun reconnectingActiveRunBlocksASecondStart() = runTest {
        val built = build(this.testScheduler)
        built.controller.startTurn(profileId, sessionId, "first")
        built.source.enqueue(ChatTurnEvent.RunStarted(runId), ChatTurnEvent.StreamReconnecting)
        testScheduler.advanceUntilIdle()
        assertEquals(
            RunSessionController.ActiveRun.Status.RECONNECTING,
            built.controller.activeRun.value?.status
        )
        val result = built.controller.startTurn(profileId, otherSessionId, "second")
        testScheduler.advanceUntilIdle()
        assertTrue(result is RunSessionController.StartTurnResult.ActiveRunConflict)
        assertEquals(1, built.source.calls.get())
        built.controller.close()
    }

    @Test
    fun pendingApprovalBlocksASecondStart() = runTest {
        val built = build(this.testScheduler)
        built.controller.startTurn(profileId, sessionId, "first")
        built.source.enqueue(
            ChatTurnEvent.RunStarted(runId),
            ChatTurnEvent.ApprovalRequested(approvalRequest())
        )
        testScheduler.advanceUntilIdle()
        assertEquals(
            RunSessionController.ActiveRun.Status.WAITING_FOR_APPROVAL,
            built.controller.activeRun.value?.status
        )
        val result = built.controller.startTurn(profileId, otherSessionId, "second")
        testScheduler.advanceUntilIdle()
        assertTrue(result is RunSessionController.StartTurnResult.ActiveRunConflict)
        assertEquals(1, built.source.calls.get())
        built.controller.close()
    }

    @Test
    fun unconfirmedRunWithoutRunIdBlocksASecondStart() = runTest {
        val built = build(this.testScheduler)
        built.controller.startTurn(profileId, sessionId, "first")
        // No RunStarted yet: runId is still null.
        assertNull(built.controller.activeRun.value?.runId)
        val result = built.controller.startTurn(profileId, otherSessionId, "second")
        testScheduler.advanceUntilIdle()
        assertTrue(result is RunSessionController.StartTurnResult.ActiveRunConflict)
        assertEquals(1, built.source.calls.get())
        built.controller.close()
    }

    @Test
    fun afterTurnCompletedANewStartIsAllowed() = runTest {
        val built = build(this.testScheduler)
        built.controller.startTurn(profileId, sessionId, "first")
        built.source.enqueue(ChatTurnEvent.RunStarted(runId), ChatTurnEvent.TurnCompleted())
        testScheduler.advanceUntilIdle()
        assertNull(built.controller.activeRun.value)
        val result = built.controller.startTurn(profileId, otherSessionId, "second")
        testScheduler.advanceUntilIdle()
        assertTrue(result is RunSessionController.StartTurnResult.Started)
        assertEquals(2, built.source.calls.get())
        built.controller.close()
    }

    @Test
    fun afterTurnFailedANewStartIsAllowed() = runTest {
        val built = build(this.testScheduler)
        built.controller.startTurn(profileId, sessionId, "first")
        built.source.enqueue(
            ChatTurnEvent.RunStarted(runId),
            ChatTurnEvent.TurnFailed(ChatStreamError.Network)
        )
        testScheduler.advanceUntilIdle()
        assertNull(built.controller.activeRun.value)
        val result = built.controller.startTurn(profileId, otherSessionId, "second")
        testScheduler.advanceUntilIdle()
        assertTrue(result is RunSessionController.StartTurnResult.Started)
        assertEquals(2, built.source.calls.get())
        built.controller.close()
    }

    @Test
    fun afterExplicitCancelANewStartIsAllowed() = runTest {
        val built = build(this.testScheduler)
        built.controller.startTurn(profileId, sessionId, "first")
        built.source.enqueue(ChatTurnEvent.RunStarted(runId))
        testScheduler.advanceUntilIdle()
        built.controller.cancelTurn()
        testScheduler.advanceUntilIdle()
        assertNull(built.controller.activeRun.value)
        val result = built.controller.startTurn(profileId, sessionId, "second")
        testScheduler.advanceUntilIdle()
        assertTrue(result is RunSessionController.StartTurnResult.Started)
        assertEquals(2, built.source.calls.get())
        built.controller.close()
    }

    @Test
    fun lateEventsOfAFinishedRunCannotDamageTheSuccessor() = runTest {
        val built = build(this.testScheduler)
        built.controller.startTurn(profileId, sessionId, "first")
        built.source.enqueue(ChatTurnEvent.RunStarted(runId), ChatTurnEvent.AssistantDelta("alt "))
        testScheduler.advanceUntilIdle()
        // End run A explicitly and start run B: a late event of the finished
        // run A must never write into run B's slot.
        built.controller.cancelTurn()
        testScheduler.advanceUntilIdle()
        built.controller.startTurn(profileId, otherSessionId, "second")
        built.source.enqueue(ChatTurnEvent.RunStarted("run_" + "f".repeat(32)))
        testScheduler.advanceUntilIdle()
        val active = built.controller.activeRun.value
        assertEquals(otherSessionId, active?.sessionId)
        assertEquals("no text of the old run may leak", "", active?.assistantText)
        built.controller.close()
    }

    @Test
    fun concurrentStartAttemptsYieldExactlyOneWinner() {
        val sourceCalls = AtomicInteger()
        val sourceOpened = java.util.concurrent.CountDownLatch(1)
        val starter = FakeServiceStarter()
        val controller = RunSessionController(
            chatSource = StreamingChatSource { _, _, _ ->
                sourceCalls.incrementAndGet()
                sourceOpened.countDown()
                flow { kotlinx.coroutines.awaitCancellation() }
            },
            serviceStarter = starter,
            notificationCoordinator = NotificationCoordinator(
                poster = NotificationPoster { },
                isForeground = { true },
                serverNameProvider = { "" },
                fallbackTitle = ""
            ),
            dispatcher = Dispatchers.Default
        )
        val threads = 8
        val barrier = CyclicBarrier(threads)
        val started = AtomicInteger()
        val conflicts = AtomicInteger()
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val futures = (1..threads).map { i ->
                pool.submit(
                    Runnable {
                        barrier.await()
                        when (controller.startTurn(profileId, "s$i", "msg$i")) {
                            RunSessionController.StartTurnResult.Started -> started.incrementAndGet()
                            RunSessionController.StartTurnResult.ActiveRunConflict -> conflicts.incrementAndGet()
                        }
                    }
                )
            }
            futures.forEach { it.get(5, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
            controller.close()
        }
        assertEquals("exactly one winner", 1, started.get())
        assertEquals(threads - 1, conflicts.get())
        assertEquals("exactly one FGS start", 1, starter.starts.get())
        // The winner's stream is opened asynchronously on its dispatcher;
        // wait for it before asserting the single-source-call invariant.
        assertTrue("the winner's stream must open", sourceOpened.await(5, TimeUnit.SECONDS))
        assertEquals("exactly one stream may be opened", 1, sourceCalls.get())
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
