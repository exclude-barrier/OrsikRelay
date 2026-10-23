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
 * JVM unit tests for the app-level [RunSessionController] (background
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
        foreground: Boolean = false,
        leaseStore: RunLeaseStore = NoOpRunLeaseStore,
        isLeaseRestorable: (RunLease) -> Boolean = { true },
        ioDispatcher: kotlinx.coroutines.CoroutineDispatcher? = null
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
                dispatcher = UnconfinedTestDispatcher(scheduler),
                ioDispatcher = ioDispatcher ?: UnconfinedTestDispatcher(scheduler),
                leaseStore = leaseStore,
                isLeaseRestorable = isLeaseRestorable
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
    // Durable in-memory turn state for re-created screens
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

        // The previous replacement semantics is gone. A second start
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
    fun stoppedRunCancelledTerminalReleasesTheSlotAuthoritativelyNotUncertain() = runTest {
        // After an accepted stop the official core ends the turn
        // with the terminal run.cancelled, which the transport maps to a
        // success TurnCompleted. The controller must release the run slot
        // as an AUTHORITATIVE terminal and never leave it UNCERTAIN (the
        // pre-fix IncompatibleApi outcome).
        val built = build(this.testScheduler)
        built.controller.startTurn(profileId, sessionId, "hi")
        built.source.enqueue(ChatTurnEvent.RunStarted(runId))
        built.source.enqueue(ChatTurnEvent.AssistantDelta("teil "))
        built.source.enqueue(ChatTurnEvent.TurnCompleted())
        testScheduler.advanceUntilIdle()

        assertNull(
            "a server-confirmed cancelled end releases the slot",
            built.controller.activeRun.value
        )
        assertTrue(built.starter.stops.get() >= 1)
        built.controller.close()
    }

    @Test
    fun cancelTurnClearsTheReconstructableTurnState() = runTest {
        val built = build(this.testScheduler)
        built.controller.startTurn(profileId, sessionId, "hi")
        built.source.enqueue(ChatTurnEvent.RunStarted(runId))
        built.source.enqueue(ChatTurnEvent.AssistantDelta("teil "))
        testScheduler.advanceUntilIdle()
        built.controller.cancelTurn(null)
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
    fun unconfirmedTransportFailureStopsForegroundServiceButKeepsOwnershipUncertain() = runTest {
        val built = build(this.testScheduler)
        built.controller.startTurn(profileId, sessionId, "hi")
        // No RunStarted: the POST may already have been accepted server-side,
        // so a local transport failure must NOT free the slot.
        built.source.enqueue(ChatTurnEvent.TurnFailed(ChatStreamError.Network))
        testScheduler.advanceUntilIdle()
        assertTrue(built.starter.stops.get() >= 1)
        val active = built.controller.activeRun.value
        assertNotNull("the possibly-live run keeps the slot", active)
        assertTrue(active?.uncertain == true)
        assertEquals(RunSessionController.ActiveRun.Status.UNCERTAIN, active?.status)
        built.controller.close()
    }

    @Test
    fun streamClosedWithoutTerminalKeepsOwnershipUncertain() = runTest {
        val built = build(this.testScheduler)
        built.controller.startTurn(profileId, sessionId, "hi")
        built.source.complete()
        testScheduler.advanceUntilIdle()
        // The defensive synthetic terminal is UNKNOWN (no confirmed run), so
        // the slot stays owned; only the background continuity is dropped.
        assertTrue(built.starter.stops.get() >= 1)
        assertNotNull(built.controller.activeRun.value)
        assertTrue(built.controller.activeRun.value?.uncertain == true)
        built.controller.close()
    }

    @Test
    fun cancelTurnCancelsStreamStopsServiceAndClearsSlot() = runTest {
        val built = build(this.testScheduler)
        built.controller.startTurn(profileId, sessionId, "hi")
        assertTrue(built.source.opened.await(1, TimeUnit.SECONDS))
        built.controller.cancelTurn(null)
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
        // Run A is released explicitly (no replacement); a late "answer"
        // of run A arrives only after run B owns the slot.
        built.controller.cancelTurn(null)
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
    // Global run ownership (single slot, no replacement)
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
    fun afterUnknownTurnFailureANewStartIsStillRejected() = runTest {
        val built = build(this.testScheduler)
        built.controller.startTurn(profileId, sessionId, "first")
        built.source.enqueue(
            ChatTurnEvent.RunStarted(runId),
            ChatTurnEvent.TurnFailed(ChatStreamError.Network)
        )
        testScheduler.advanceUntilIdle()
        // A confirmed run plus a transport failure is UNKNOWN - the
        // ownership is kept and no second POST may start.
        assertNotNull(built.controller.activeRun.value)
        assertTrue(built.controller.activeRun.value?.uncertain == true)
        val result = built.controller.startTurn(profileId, otherSessionId, "second")
        testScheduler.advanceUntilIdle()
        assertTrue(result is RunSessionController.StartTurnResult.ActiveRunConflict)
        assertEquals("no second POST", 1, built.source.calls.get())
        built.controller.close()
    }

    @Test
    fun afterAuthoritativeServerFailureANewStartIsAllowed() = runTest {
        val built = build(this.testScheduler)
        built.controller.startTurn(profileId, sessionId, "first")
        built.source.enqueue(
            ChatTurnEvent.RunStarted(runId),
            ChatTurnEvent.TurnFailed(ChatStreamError.ServerError)
        )
        testScheduler.advanceUntilIdle()
        // The server's own terminal `error` frame is authoritative: the slot
        // is released and a new run may start.
        assertNull(built.controller.activeRun.value)
        val result = built.controller.startTurn(profileId, otherSessionId, "second")
        testScheduler.advanceUntilIdle()
        assertTrue(result is RunSessionController.StartTurnResult.Started)
        assertEquals(2, built.source.calls.get())
        built.controller.close()
    }

    // ------------------------------------------------------------------
    // Authoritative-only release and the uncertain recovery state
    // ------------------------------------------------------------------

    @Test
    fun confirmedTransportFailureKeepsOwnershipUncertainAndStopsTheService() = runTest {
        val built = build(this.testScheduler)
        built.controller.startTurn(profileId, sessionId, "hi")
        built.source.enqueue(
            ChatTurnEvent.RunStarted(runId),
            ChatTurnEvent.TurnFailed(ChatStreamError.Network)
        )
        testScheduler.advanceUntilIdle()
        val active = built.controller.activeRun.value
        assertNotNull("a confirmed run must not be forgotten on a transport failure", active)
        assertEquals(runId, active?.runId)
        assertTrue(active?.uncertain == true)
        assertEquals(RunSessionController.ActiveRun.Status.UNCERTAIN, active?.status)
        assertTrue("no active transport survives, so the FGS stops", built.starter.stops.get() >= 1)
        built.controller.close()
    }

    @Test
    fun resumeGapAfterReconnectingKeepsTheSlotUncertainWithoutASecondPost() = runTest {
        val built = build(this.testScheduler)
        built.controller.startTurn(profileId, sessionId, "hi")
        built.source.enqueue(
            ChatTurnEvent.RunStarted(runId),
            ChatTurnEvent.StreamReconnecting,
            ChatTurnEvent.TurnFailed(ChatStreamError.ResumeGap)
        )
        testScheduler.advanceUntilIdle()

        val active = built.controller.activeRun.value
        assertNotNull("a failed re-attach does not prove the run ended", active)
        assertEquals(runId, active?.runId)
        assertEquals(RunSessionController.ActiveRun.Status.UNCERTAIN, active?.status)
        assertFalse("the dead transport is no longer announced as reconnecting", active?.reconnecting == true)
        assertTrue("no active transport survives, so the FGS stops", built.starter.stops.get() >= 1)

        // The uncertain run keeps the single slot: a later start of the
        // same session must be refused and must never POST again.
        assertEquals(
            RunSessionController.StartTurnResult.ActiveRunConflict,
            built.controller.startTurn(profileId, sessionId, "again")
        )
        assertEquals("exactly one stream/POST", 1, built.source.calls.get())
        built.controller.close()
    }

    @Test
    fun terminalDirectlyAfterReconnectingReleasesSlotLeaseAndService() = runTest {
        val store = FakeLeaseStore()
        val built = build(this.testScheduler, leaseStore = store)
        built.controller.startTurn(profileId, sessionId, "hi")
        built.source.enqueue(ChatTurnEvent.RunStarted(runId), ChatTurnEvent.StreamReconnecting)
        testScheduler.advanceUntilIdle()
        assertEquals(
            RunSessionController.ActiveRun.Status.RECONNECTING,
            built.controller.activeRun.value?.status
        )
        assertEquals(lease, store.current)

        // The re-attached stream reports the authoritative terminal: the
        // slot, the durable lease and the foreground service all settle.
        built.source.enqueue(ChatTurnEvent.TurnCompleted())
        testScheduler.advanceUntilIdle()

        assertNull(built.controller.activeRun.value)
        assertNull("a terminal after reconnecting clears the lease", store.current)
        assertEquals(1, built.starter.starts.get())
        assertTrue(built.starter.stops.get() >= 1)
        assertEquals("no second stream/POST after the terminal", 1, built.source.calls.get())
        built.controller.close()
    }

    @Test
    fun confirmedStreamEndingDuringReconnectKeepsTheSlotUncertainAndRejectsTheSameSession() = runTest {
        val built = build(this.testScheduler)
        built.controller.startTurn(profileId, sessionId, "hi")
        built.source.enqueue(ChatTurnEvent.RunStarted(runId), ChatTurnEvent.StreamReconnecting)
        testScheduler.advanceUntilIdle()

        // Bridge restart: the re-attach socket ends without any terminal
        // frame. The confirmed run may still be alive server-side.
        built.source.complete()
        testScheduler.advanceUntilIdle()

        val active = built.controller.activeRun.value
        assertNotNull(active)
        assertEquals(runId, active?.runId)
        assertEquals(RunSessionController.ActiveRun.Status.UNCERTAIN, active?.status)
        assertTrue(built.starter.stops.get() >= 1)
        assertEquals("no automatic re-POST", 1, built.source.calls.get())

        // Even the same session is refused while the outcome is unknown.
        assertEquals(
            RunSessionController.StartTurnResult.ActiveRunConflict,
            built.controller.startTurn(profileId, sessionId, "again")
        )
        assertEquals("exactly one stream/POST", 1, built.source.calls.get())
        built.controller.close()
    }

    @Test
    fun markTurnUncertainKeepsOwnershipAndCancelsTheLocalStream() = runTest {
        val built = build(this.testScheduler)
        built.controller.startTurn(profileId, sessionId, "hi")
        built.source.enqueue(ChatTurnEvent.RunStarted(runId))
        assertTrue(built.source.opened.await(1, TimeUnit.SECONDS))
        built.controller.markTurnUncertain(profileId, sessionId)
        testScheduler.advanceUntilIdle()
        val active = built.controller.activeRun.value
        assertNotNull(active)
        assertEquals(runId, active?.runId)
        assertTrue(active?.uncertain == true)
        assertTrue("the dead local stream is dropped", built.source.canceled.get())
        assertTrue(built.starter.stops.get() >= 1)
        built.controller.close()
    }

    @Test
    fun markTurnUncertainWithAForeignIdentityIsANoOp() = runTest {
        val built = build(this.testScheduler)
        built.controller.startTurn(profileId, sessionId, "hi")
        built.source.enqueue(ChatTurnEvent.RunStarted(runId))
        assertTrue(built.source.opened.await(1, TimeUnit.SECONDS))
        // A foreign profile/session must not cancel the transport or mark the
        // active run uncertain.
        built.controller.markTurnUncertain(profileId, otherSessionId)
        built.controller.markTurnUncertain("other-profile", sessionId)
        testScheduler.advanceUntilIdle()
        assertFalse(built.source.canceled.get())
        assertTrue(built.controller.activeRun.value?.uncertain != true)
        built.controller.close()
    }

    @Test
    fun confirmTerminalForUncertainRunReleasesOnlyTheExactUncertainRun() = runTest {
        val built = build(this.testScheduler)
        built.controller.startTurn(profileId, sessionId, "hi")
        built.source.enqueue(
            ChatTurnEvent.RunStarted(runId),
            ChatTurnEvent.TurnFailed(ChatStreamError.Network)
        )
        testScheduler.advanceUntilIdle()
        // Every mismatch is a no-op and leaves the ownership intact.
        assertFalse(built.controller.confirmTerminalForUncertainRun(profileId, otherSessionId, runId))
        assertFalse(built.controller.confirmTerminalForUncertainRun(profileId, sessionId, "run_" + "z".repeat(32)))
        assertFalse(built.controller.confirmTerminalForUncertainRun("other-profile", sessionId, runId))
        assertNotNull(built.controller.activeRun.value)
        // The exact correlation releases the slot and allows a new run.
        assertTrue(built.controller.confirmTerminalForUncertainRun(profileId, sessionId, runId))
        assertNull(built.controller.activeRun.value)
        val result = built.controller.startTurn(profileId, sessionId, "second")
        assertTrue(result is RunSessionController.StartTurnResult.Started)
        built.controller.close()
    }

    @Test
    fun confirmTerminalForUncertainRunRejectsANonUncertainRun() = runTest {
        val built = build(this.testScheduler)
        built.controller.startTurn(profileId, sessionId, "hi")
        built.source.enqueue(ChatTurnEvent.RunStarted(runId))
        testScheduler.advanceUntilIdle()
        assertFalse(built.controller.confirmTerminalForUncertainRun(profileId, sessionId, runId))
        assertNotNull(built.controller.activeRun.value)
        assertTrue(built.controller.activeRun.value?.uncertain != true)
        built.controller.close()
    }

    @Test
    fun aSecondStartAfterATransportErrorStillYieldsAtMostOneRun() = runTest {
        val built = build(this.testScheduler)
        built.controller.startTurn(profileId, sessionId, "first")
        built.source.enqueue(
            ChatTurnEvent.RunStarted(runId),
            ChatTurnEvent.TurnFailed(ChatStreamError.Timeout)
        )
        testScheduler.advanceUntilIdle()
        val result = built.controller.startTurn(profileId, otherSessionId, "second")
        testScheduler.advanceUntilIdle()
        assertTrue(result is RunSessionController.StartTurnResult.ActiveRunConflict)
        assertEquals("exactly one POST", 1, built.source.calls.get())
        assertEquals(runId, built.controller.activeRun.value?.runId)
        built.controller.close()
    }

    @Test
    fun anUnconfirmedFailureThatMayHaveBeenAcceptedIsNotReleased() = runTest {
        // Invariant 7: no confirmed run ID after the initial POST. The POST
        // may already have been accepted server-side, so a Network failure
        // must NOT be treated as a stopped run.
        val built = build(this.testScheduler)
        built.controller.startTurn(profileId, sessionId, "first")
        built.source.enqueue(ChatTurnEvent.TurnFailed(ChatStreamError.Network))
        testScheduler.advanceUntilIdle()
        assertNotNull(built.controller.activeRun.value)
        assertTrue(built.controller.activeRun.value?.uncertain == true)
        assertNull("no run ID is bound yet", built.controller.activeRun.value?.runId)
        assertEquals(1, built.source.calls.get())
        built.controller.close()
    }

    @Test
    fun anUnconfirmedServerUnavailableIsNotReleased() = runTest {
        // A 5xx on the streaming POST can be a gateway error after the run
        // was admitted, so it is NOT a proven pre-run rejection.
        val built = build(this.testScheduler)
        built.controller.startTurn(profileId, sessionId, "first")
        built.source.enqueue(ChatTurnEvent.TurnFailed(ChatStreamError.ServerUnavailable))
        testScheduler.advanceUntilIdle()
        assertNotNull(built.controller.activeRun.value)
        assertTrue(built.controller.activeRun.value?.uncertain == true)
        assertEquals(1, built.source.calls.get())
        built.controller.close()
    }

    @Test
    fun afterExplicitCancelANewStartIsAllowed() = runTest {
        val built = build(this.testScheduler)
        built.controller.startTurn(profileId, sessionId, "first")
        built.source.enqueue(ChatTurnEvent.RunStarted(runId))
        testScheduler.advanceUntilIdle()
        built.controller.cancelTurn(null)
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
        built.controller.cancelTurn(null)
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
            // The winner's stream is opened asynchronously on its dispatcher.
            // Wait for it HERE, while the controller's scope is still alive:
            // [RunSessionController.close] cancels that scope, so awaiting the
            // open after teardown races it and can abort the stream before the
            // source was ever called (the flake: line 839 timed out although
            // exactly one startTurn had returned Started).
            assertTrue("the winner's stream must open", sourceOpened.await(5, TimeUnit.SECONDS))
            // The global-ownership invariant is asserted from the reserved slot, not from the
            // async stream: exactly one caller won, the other seven conflicted,
            // and the winner still owns the single slot.
            assertEquals("exactly one winner", 1, started.get())
            assertEquals(threads - 1, conflicts.get())
            assertEquals("exactly one FGS start", 1, starter.starts.get())
            assertEquals("exactly one stream may be opened", 1, sourceCalls.get())
            assertNotNull("the winner still owns the single slot", controller.activeRun.value)
        } finally {
            pool.shutdownNow()
            controller.close()
        }
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

    @Test
    fun notificationFailureNeverChangesTheRunStatus() = runTest {
        val source = FakeTurnSource()
        val starter = FakeServiceStarter()
        val controller = RunSessionController(
            chatSource = source,
            serviceStarter = starter,
            notificationCoordinator = NotificationCoordinator(
                poster = NotificationPoster {
                    throw IllegalStateException("notification failure")
                },
                isForeground = { false },
                serverNameProvider = { "srv" },
                fallbackTitle = "Orsik Relay"
            ),
            dispatcher = UnconfinedTestDispatcher(testScheduler),
            ioDispatcher = UnconfinedTestDispatcher(testScheduler)
        )
        controller.startTurn(profileId, sessionId, "hi")
        source.enqueue(
            ChatTurnEvent.RunStarted(runId),
            ChatTurnEvent.ApprovalRequested(approvalRequest())
        )
        testScheduler.advanceUntilIdle()
        assertEquals(
            "an approval notification failure must not fail the run",
            RunSessionController.ActiveRun.Status.WAITING_FOR_APPROVAL,
            controller.activeRun.value?.status
        )

        source.enqueue(ChatTurnEvent.TurnCompleted())
        testScheduler.advanceUntilIdle()
        assertNull(
            "a run-completed notification failure must not keep the slot",
            controller.activeRun.value
        )
        assertEquals("the terminal path still stops the service", 1, starter.stops.get())
        controller.close()
    }

    // ------------------------------------------------------------------
    // Persisted run lease for process-death recovery
    // ------------------------------------------------------------------

    private val lease get() = RunLease(profileId, sessionId, runId)

    @Test
    fun confirmedRunPersistsTheLeaseAndAuthoritativeTerminalClearsIt() = runTest {
        val store = FakeLeaseStore()
        val built = build(this.testScheduler, leaseStore = store)
        built.controller.startTurn(profileId, sessionId, "hi")
        testScheduler.advanceUntilIdle()
        assertNull("an unconfirmed run has no run id to recover", store.current)

        built.source.enqueue(ChatTurnEvent.RunStarted(runId))
        testScheduler.advanceUntilIdle()
        assertEquals(lease, store.current)

        built.source.enqueue(ChatTurnEvent.TurnCompleted())
        testScheduler.advanceUntilIdle()
        assertNull(store.current)
        built.controller.close()
    }

    @Test
    fun transportFailureKeepsTheLeaseOfTheUncertainRun() = runTest {
        val store = FakeLeaseStore()
        val built = build(this.testScheduler, leaseStore = store)
        built.controller.startTurn(profileId, sessionId, "hi")
        built.source.enqueue(ChatTurnEvent.RunStarted(runId))
        built.source.enqueue(ChatTurnEvent.TurnFailed(ChatStreamError.Network))
        testScheduler.advanceUntilIdle()
        assertTrue(built.controller.activeRun.value?.uncertain == true)
        assertEquals(lease, store.current)
        built.controller.close()
    }

    @Test
    fun closeKeepsThePersistedLease() = runTest {
        val store = FakeLeaseStore()
        val built = build(this.testScheduler, leaseStore = store)
        built.controller.startTurn(profileId, sessionId, "hi")
        built.source.enqueue(ChatTurnEvent.RunStarted(runId))
        testScheduler.advanceUntilIdle()
        built.controller.close()
        testScheduler.advanceUntilIdle()
        assertEquals("teardown is not an authoritative terminal", lease, store.current)
    }

    @Test
    fun restoredLeaseOwnsTheSlotAsUncertainAndBlocksANewPost() = runTest {
        val store = FakeLeaseStore(lease)
        val built = build(this.testScheduler, leaseStore = store)
        testScheduler.advanceUntilIdle()

        val active = built.controller.activeRun.value
        assertNotNull(active)
        assertEquals(profileId, active?.profileId)
        assertEquals(sessionId, active?.sessionId)
        assertEquals(runId, active?.runId)
        assertEquals(RunSessionController.ActiveRun.Status.UNCERTAIN, active?.status)

        assertEquals(
            RunSessionController.StartTurnResult.ActiveRunConflict,
            built.controller.startTurn(profileId, sessionId, "again")
        )
        assertEquals("no chat POST after process death", 0, built.source.calls.get())
        assertEquals(0, built.starter.starts.get())
        assertEquals(lease, store.current)
        built.controller.close()
    }

    @Test
    fun reconciliationReleasesTheRestoredRunAndClearsTheLease() = runTest {
        val store = FakeLeaseStore(lease)
        val built = build(this.testScheduler, leaseStore = store)
        testScheduler.advanceUntilIdle()

        assertFalse(built.controller.confirmTerminalForUncertainRun(profileId, sessionId, "run_other"))
        assertTrue(built.controller.confirmTerminalForUncertainRun(profileId, sessionId, runId))
        testScheduler.advanceUntilIdle()
        assertNull(built.controller.activeRun.value)
        assertNull(store.current)
        assertEquals(
            RunSessionController.StartTurnResult.Started,
            built.controller.startTurn(profileId, sessionId, "next")
        )
        built.controller.close()
    }

    @Test
    fun leaseOfAnUnknownProfileIsDroppedOnRestore() = runTest {
        val store = FakeLeaseStore(lease)
        val built = build(this.testScheduler, leaseStore = store, isLeaseRestorable = { false })
        testScheduler.advanceUntilIdle()
        assertNull(built.controller.activeRun.value)
        assertNull(store.current)
        built.controller.close()
    }

    @Test
    fun unreadableLeaseStoreDoesNotCrashAndLeavesTheSlotFree() = runTest {
        val store = FakeLeaseStore(failLoad = true)
        val built = build(this.testScheduler, leaseStore = store)
        testScheduler.advanceUntilIdle()
        assertNull(built.controller.activeRun.value)
        assertEquals(
            RunSessionController.StartTurnResult.Started,
            built.controller.startTurn(profileId, sessionId, "hi")
        )
        built.controller.close()
    }

    @Test
    fun anUnreadableLeaseIsKeptNotCleared() = runTest {
        val store = FakeLeaseStore(initial = lease, failLoad = true)
        val built = build(this.testScheduler, leaseStore = store)
        testScheduler.advanceUntilIdle()
        // Unreadable now is not "no lease": a later start may still read it.
        assertEquals(lease, store.current)
        built.controller.close()
    }

    @Test
    fun theUserCanReleaseAnUnconfirmedUncertainRunOnlyForItsOwnChat() = runTest {
        val built = build(this.testScheduler)
        built.controller.startTurn(profileId, sessionId, "hi")
        built.source.enqueue(ChatTurnEvent.TurnFailed(ChatStreamError.Network))
        testScheduler.advanceUntilIdle()
        assertTrue(built.controller.activeRun.value?.uncertain == true)

        assertFalse(built.controller.releaseUncertainByUser(profileId, "other-session"))
        assertNotNull(built.controller.activeRun.value)
        assertTrue(built.controller.releaseUncertainByUser(profileId, sessionId))
        assertNull(built.controller.activeRun.value)
        built.controller.close()
    }

    @Test
    fun aLiveRunCannotBeReleasedByTheUser() = runTest {
        val built = build(this.testScheduler)
        built.controller.startTurn(profileId, sessionId, "hi")
        assertTrue(built.source.opened.await(1, TimeUnit.SECONDS))
        assertFalse(built.controller.releaseUncertainByUser(profileId, sessionId))
        assertNotNull(built.controller.activeRun.value)
        built.controller.close()
    }

    @Test
    fun aLateStopAnswerForAnotherRunNeverCancelsTheCurrentOne() = runTest {
        val built = build(this.testScheduler)
        built.controller.startTurn(profileId, sessionId, "hi")
        built.source.enqueue(ChatTurnEvent.RunStarted("run_current"))
        testScheduler.advanceUntilIdle()

        built.controller.cancelTurn("run_old")
        assertNotNull("a foreign run id is a no-op", built.controller.activeRun.value)
        built.controller.cancelTurn("run_current")
        testScheduler.advanceUntilIdle()
        assertNull(built.controller.activeRun.value)
        built.controller.close()
    }

    @Test
    fun startIsRefusedUntilTheLeaseRestoreFinished() = runTest {
        val store = FakeLeaseStore(lease)
        val built = build(
            this.testScheduler,
            leaseStore = store,
            ioDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(this.testScheduler)
        )
        // The restore has not run yet: a start must not race it.
        assertEquals(
            RunSessionController.StartTurnResult.ActiveRunConflict,
            built.controller.startTurn(profileId, sessionId, "early")
        )
        assertEquals(0, built.source.calls.get())
        testScheduler.advanceUntilIdle()
        assertEquals(runId, built.controller.activeRun.value?.runId)
        assertTrue(built.controller.activeRun.value?.uncertain == true)
        built.controller.close()
    }

    // ------------------------------------------------------------------
    // Fakes
    // ------------------------------------------------------------------

    /** In-memory lease store; [failLoad] simulates an unreadable store. */
    private class FakeLeaseStore(
        initial: RunLease? = null,
        private val failLoad: Boolean = false
    ) : RunLeaseStore {
        @Volatile
        var current: RunLease? = initial

        override fun load(): RunLease? {
            if (failLoad) throw IllegalStateException("unreadable")
            return current
        }

        override fun save(lease: RunLease) {
            current = lease
        }

        override fun clear() {
            current = null
        }
    }

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
