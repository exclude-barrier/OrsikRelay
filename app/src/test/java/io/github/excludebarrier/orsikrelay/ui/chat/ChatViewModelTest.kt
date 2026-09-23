package io.github.excludebarrier.orsikrelay.ui.chat

import io.github.excludebarrier.orsikrelay.data.chat.ChatDraftRepository
import io.github.excludebarrier.orsikrelay.domain.chat.ChatStreamError
import io.github.excludebarrier.orsikrelay.domain.chat.ChatTurnEvent
import io.github.excludebarrier.orsikrelay.domain.chat.StreamingChatSource
import io.github.excludebarrier.orsikrelay.domain.message.HermesChatMessage
import io.github.excludebarrier.orsikrelay.domain.message.MessageHistoryError
import io.github.excludebarrier.orsikrelay.domain.message.MessageHistoryResult
import io.github.excludebarrier.orsikrelay.domain.message.MessageHistorySource
import io.github.excludebarrier.orsikrelay.domain.message.MessagePage
import io.github.excludebarrier.orsikrelay.domain.message.MessageRole
import io.github.excludebarrier.orsikrelay.domain.run.ApprovalRequest
import io.github.excludebarrier.orsikrelay.domain.run.RunSteerError
import io.github.excludebarrier.orsikrelay.domain.run.RunSteerRequester
import io.github.excludebarrier.orsikrelay.domain.run.RunSteerResult
import io.github.excludebarrier.orsikrelay.domain.run.RunStopError
import io.github.excludebarrier.orsikrelay.domain.run.RunStopRequester
import io.github.excludebarrier.orsikrelay.domain.run.RunStopResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the [ChatViewModel] turn state machine (P1 refactor).
 *
 * These tests prove the verified P0 invariants WITHOUT a device: history
 * gating, the send/streaming lifecycle, run stop, run steer, recoverable
 * steers, draft persistence, back and approval delivery. Every seam is a
 * synthetic fake; no network, no credentials, no real server. The view model
 * is driven with a virtual-time [UnconfinedTestDispatcher], so the collector,
 * stop and steer coroutines run deterministically.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

    private val profileId = "p"
    private val sessionId = "s"
    private val runId = "run_" + "a".repeat(32)
    private val requestId = "b".repeat(32)

    private fun readyHistory(vararg messages: HermesChatMessage): MessageHistorySource =
        MessageHistorySource { _, _ ->
            MessageHistoryResult.Success(MessagePage(messages.toList()))
        }

    private fun msg(id: Long, role: MessageRole, content: String): HermesChatMessage =
        HermesChatMessage(id = id, role = role, content = content, timestampSeconds = null, toolName = null)

    private fun build(
        scheduler: kotlinx.coroutines.test.TestCoroutineScheduler,
        history: MessageHistorySource = readyHistory(),
        chat: StreamingChatSource = FakeTurnSource(),
        stop: RunStopRequester = FakeStopRequester(),
        steer: RunSteerRequester = FakeSteerRequester(),
        draft: ChatDraftRepository = FakeDraftRepository(),
        onBack: () -> Unit = {}
    ): ChatViewModel = ChatViewModel(
        historySource = history,
        chatSource = chat,
        runStopRequester = stop,
        runSteerRequester = steer,
        chatDraftRepository = draft,
        profileId = profileId,
        sessionId = sessionId,
        onBack = onBack,
        toolActivityLabel = "Tool ist aktiv…",
        namedToolActivityLabel = "Werkzeug %s ist aktiv…",
        dispatcher = UnconfinedTestDispatcher(scheduler),
        ioDispatcher = UnconfinedTestDispatcher(scheduler)
    )

    /** Creates the model and loads (empty) history so the composer is enabled. */
    private fun withModel(
        history: MessageHistorySource = readyHistory(),
        onBack: () -> Unit = {},
        block: suspend kotlinx.coroutines.test.TestScope.(
            model: ChatViewModel,
            chat: FakeTurnSource,
            stop: FakeStopRequester,
            steer: FakeSteerRequester,
            draft: FakeDraftRepository
        ) -> Unit
    ) = kotlinx.coroutines.test.runTest {
        val chat = FakeTurnSource()
        val stop = FakeStopRequester()
        val steer = FakeSteerRequester()
        val draft = FakeDraftRepository()
        val model = build(this.testScheduler, history = history, chat = chat, stop = stop, steer = steer, draft = draft, onBack = onBack)
        model.loadHistory()
        testScheduler.advanceUntilIdle()
        block(model, chat, stop, steer, draft)
        model.close()
        testScheduler.advanceUntilIdle()
    }

    // ------------------------------------------------------------------
    // History + composer gating
    // ------------------------------------------------------------------

    @Test
    fun historyLoadsToReady() = withModel { model, _, _, _, _ ->
        assertTrue(model.state.value.history is MessageHistoryState.Ready)
    }

    @Test
    fun historyFailureIsGeneric() = withModel(
        history = MessageHistorySource { _, _ ->
            MessageHistoryResult.Failure(MessageHistoryError.Network)
        }
    ) { model, _, _, _, _ ->
        assertTrue(model.state.value.history is MessageHistoryState.Failed)
    }

    @Test
    fun sendBeforeHistoryReadyIsNoOp() = withModel(
        history = MessageHistorySource { _, _ ->
            MessageHistoryResult.Failure(MessageHistoryError.Network)
        }
    ) { model, chat, _, _, _ ->
        model.onDraftChange("hallo")
        model.send()
        testScheduler.advanceUntilIdle()
        assertEquals(0, chat.calls.get())
        assertFalse(model.state.value.turnActive)
    }

    @Test
    fun sendBlankOrInvalidDraftIsNoOp() = withModel { model, chat, _, _, _ ->
        model.onDraftChange("   ")
        model.send()
        testScheduler.advanceUntilIdle()
        assertEquals(0, chat.calls.get())
        assertFalse(model.state.value.turnActive)
    }

    @Test
    fun doubleSendStartsExactlyOneStream() = withModel { model, chat, _, _, _ ->
        model.onDraftChange("hallo")
        model.send()
        model.send()
        testScheduler.advanceUntilIdle()
        assertTrue(chat.opened.await(1, TimeUnit.SECONDS))
        assertEquals(1, chat.calls.get())
    }

    // ------------------------------------------------------------------
    // Streaming lifecycle
    // ------------------------------------------------------------------

    @Test
    fun userLineAppearsImmediatelyAndDraftKeptUntilRunStarted() = withModel { model, chat, _, _, _ ->
        model.onDraftChange("  Hallo  ")
        model.send()
        testScheduler.advanceUntilIdle()
        assertTrue(model.state.value.turnActive)
        // Local user line shows the TRIMMED message immediately.
        val lines = model.state.value.localLines
        assertEquals(1, lines.size)
        assertEquals(MessageRole.USER, lines.first().role)
        assertEquals("Hallo", lines.first().content)
        // Draft is NOT cleared before the confirmed start.
        assertEquals("  Hallo  ", model.state.value.draft)
        assertEquals(null, model.state.value.boundRunId)

        chat.enqueue(ChatTurnEvent.RunStarted(runId))
        testScheduler.advanceUntilIdle()
        assertEquals(runId, model.state.value.boundRunId)
        assertTrue(model.state.value.runStarted)
        // RunStarted clears the composer AND its stored state.
        assertEquals("", model.state.value.draft)
    }

    @Test
    fun deltasComposeOneLineAndFinalReplacesIt() = withModel { model, chat, _, _, _ ->
        model.onDraftChange("hi")
        model.send()
        chat.enqueue(
            ChatTurnEvent.RunStarted(runId),
            ChatTurnEvent.AssistantStarted,
            ChatTurnEvent.AssistantDelta("Delta-1"),
            ChatTurnEvent.AssistantDelta("Delta-2")
        )
        testScheduler.advanceUntilIdle()
        val assistant = model.state.value.localLines.filter { it.role == MessageRole.ASSISTANT }
        assertEquals(1, assistant.size)
        assertEquals("Delta-1Delta-2", assistant.single().content)

        chat.enqueue(ChatTurnEvent.AssistantCompleted("Finaler Text"), ChatTurnEvent.TurnCompleted())
        chat.complete()
        testScheduler.advanceUntilIdle()
        val assistantAfter = model.state.value.localLines.filter { it.role == MessageRole.ASSISTANT }
        assertEquals("Finaler Text", assistantAfter.single().content)
        assertFalse(model.state.value.turnActive)
    }

    @Test
    fun toolActivityRendersGenericLabelOnly() = withModel { model, chat, _, _, _ ->
        model.onDraftChange("tool")
        model.send()
        chat.enqueue(ChatTurnEvent.RunStarted(runId), ChatTurnEvent.ToolActivity())
        testScheduler.advanceUntilIdle()
        val tools = model.state.value.localLines.filter { it.role == MessageRole.TOOL }
        assertEquals(1, tools.size)
        assertEquals("Tool ist aktiv…", tools.single().content)
        assertTrue(tools.single().localTool)
    }

    @Test
    fun streamEndingWithoutTerminalEventIsNetworkFailure() = withModel { model, chat, _, _, _ ->
        model.onDraftChange("hi")
        model.send()
        testScheduler.advanceUntilIdle()
        // The flow closes with NO terminal event.
        chat.complete()
        testScheduler.advanceUntilIdle()
        assertEquals(ChatStreamError.Network, model.state.value.turnFailure)
        assertFalse(model.state.value.turnActive)
    }

    @Test
    fun failureBeforeRunStartDropsPrematureUserLineAndKeepsDraft() = withModel { model, chat, _, _, _ ->
        model.onDraftChange("hi")
        model.send()
        testScheduler.advanceUntilIdle()
        assertEquals(1, model.state.value.localLines.size)
        chat.enqueue(ChatTurnEvent.TurnFailed(ChatStreamError.Timeout))
        chat.complete()
        testScheduler.advanceUntilIdle()
        assertEquals(ChatStreamError.Timeout, model.state.value.turnFailure)
        // Premature user line dropped; draft kept for a deliberate retry.
        assertTrue(model.state.value.localLines.isEmpty())
        assertEquals("hi", model.state.value.draft)
        assertFalse(model.state.value.turnActive)
    }

    @Test
    fun failureAfterRunStartKeepsUserLine() = withModel { model, chat, _, _, _ ->
        model.onDraftChange("hi")
        model.send()
        chat.enqueue(ChatTurnEvent.RunStarted(runId), ChatTurnEvent.TurnFailed(ChatStreamError.Network))
        chat.complete()
        testScheduler.advanceUntilIdle()
        assertEquals(ChatStreamError.Network, model.state.value.turnFailure)
        // The user line is kept: the run had a confirmed start. (P0 does not
        // reset `runStarted` on a failed turn; only the user line matters.)
        assertTrue(model.state.value.localLines.any { it.role == MessageRole.USER })
    }

    // ------------------------------------------------------------------
    // Approval
    // ------------------------------------------------------------------

    private fun approval(): ApprovalRequest =
        ApprovalRequest(runId = runId, requestId = requestId, description = ApprovalRequest.SAFE_DESCRIPTION)

    @Test
    fun approvalRequestedSetsPendingRequest() = withModel { model, chat, _, _, _ ->
        model.onDraftChange("hi")
        model.send()
        chat.enqueue(ChatTurnEvent.RunStarted(runId), ChatTurnEvent.ApprovalRequested(approval()))
        testScheduler.advanceUntilIdle()
        assertEquals(approval(), model.state.value.pendingApproval)
        // The turn stays active while the approval is pending.
        assertTrue(model.state.value.turnActive)
    }

    @Test
    fun secondPendingApprovalIsIncompatibleApi() = withModel { model, chat, _, _, _ ->
        model.onDraftChange("hi")
        model.send()
        chat.enqueue(ChatTurnEvent.RunStarted(runId), ChatTurnEvent.ApprovalRequested(approval()))
        testScheduler.advanceUntilIdle()
        // A second request while one is still pending is a protocol contradiction.
        chat.enqueue(ChatTurnEvent.ApprovalRequested(approval()))
        chat.complete()
        testScheduler.advanceUntilIdle()
        assertEquals(ChatStreamError.IncompatibleApi, model.state.value.turnFailure)
        assertNull(model.state.value.pendingApproval)
        assertFalse(model.state.value.turnActive)
    }

    @Test
    fun dismissApprovalClearsPending() = withModel { model, chat, _, _, _ ->
        model.onDraftChange("hi")
        model.send()
        chat.enqueue(ChatTurnEvent.RunStarted(runId), ChatTurnEvent.ApprovalRequested(approval()))
        testScheduler.advanceUntilIdle()
        model.dismissApproval()
        assertNull(model.state.value.pendingApproval)
    }

    // ------------------------------------------------------------------
    // Run stop
    // ------------------------------------------------------------------

    @Test
    fun stopIsInvisibleAndNoOpBeforeRunStarted() = withModel { model, chat, stop, _, _ ->
        model.onDraftChange("hi")
        model.send()
        testScheduler.advanceUntilIdle()
        assertFalse(model.state.value.controlsVisible)
        model.stop()
        testScheduler.advanceUntilIdle()
        assertEquals(0, stop.calls.get())
    }

    @Test
    fun stopAcceptedSetsNoOptimisticEnd() = withModel { model, chat, stop, _, _ ->
        model.onDraftChange("hi")
        model.send()
        chat.enqueue(ChatTurnEvent.RunStarted(runId))
        testScheduler.advanceUntilIdle()
        assertTrue(model.state.value.controlsVisible)
        model.stop()
        assertTrue(stop.started.await(1, TimeUnit.SECONDS))
        testScheduler.advanceUntilIdle()
        assertEquals(1, stop.calls.get())
        // Accepted: the run stays active (no optimistic end) and the accepted
        // stop stays in flight until the turn's own terminal event settles it.
        assertTrue(model.state.value.turnActive)
        assertTrue(model.state.value.stopInFlight)
    }

    @Test
    fun stopDoubleTapStartsExactlyOneRequest() = withModel { model, chat, stop, _, _ ->
        model.onDraftChange("hi")
        model.send()
        chat.enqueue(ChatTurnEvent.RunStarted(runId))
        testScheduler.advanceUntilIdle()
        model.stop()
        model.stop()
        testScheduler.advanceUntilIdle()
        assertEquals(1, stop.calls.get())
    }

    @Test
    fun stopFailureLeavesOneDeliberateRetry() = withModel { model, chat, stop, _, _ ->
        stop.result = RunStopResult.Failure(RunStopError.Network)
        model.onDraftChange("hi")
        model.send()
        chat.enqueue(ChatTurnEvent.RunStarted(runId))
        testScheduler.advanceUntilIdle()
        model.stop()
        testScheduler.advanceUntilIdle()
        assertTrue(model.state.value.stopFailed)
        assertFalse(model.state.value.stopInFlight)
        // The running stream is left untouched.
        assertTrue(model.state.value.turnActive)
    }

    @Test
    fun stopAlreadyTerminalReconcilesAndCancelsStream() = withModel { model, chat, stop, _, _ ->
        stop.result = RunStopResult.AlreadyTerminal(runId)
        model.onDraftChange("hi")
        model.send()
        assertTrue(chat.opened.await(1, TimeUnit.SECONDS))
        chat.enqueue(ChatTurnEvent.RunStarted(runId))
        testScheduler.advanceUntilIdle()
        model.stop()
        testScheduler.advanceUntilIdle()
        // Reconciliation: ephemeral turn state reset, collector cancelled,
        // history re-read. The chat POST is never resent.
        assertFalse(model.state.value.turnActive)
        assertTrue(chat.canceled.get())
        assertEquals(1, stop.calls.get())
        assertTrue(model.state.value.history is MessageHistoryState.Ready)
    }

    // ------------------------------------------------------------------
    // Run steer
    // ------------------------------------------------------------------

    @Test
    fun steerIsNoOpBeforeRunStarted() = withModel { model, chat, _, steer, _ ->
        model.onDraftChange("hi")
        model.send()
        model.onSteerDraftChange("steer")
        model.steer()
        testScheduler.advanceUntilIdle()
        assertEquals(0, steer.calls.get())
    }

    @Test
    fun steerQueuedAcknowledgesAndClearsInput() = withModel { model, chat, _, steer, _ ->
        model.onDraftChange("hi")
        model.send()
        chat.enqueue(ChatTurnEvent.RunStarted(runId))
        model.onSteerDraftChange("  Kurs  ")
        model.steer()
        assertTrue(steer.started.await(1, TimeUnit.SECONDS))
        testScheduler.advanceUntilIdle()
        // The EXACT validated/trimmed text is sent, not the raw draft.
        assertEquals("Kurs", steer.text)
        assertTrue(model.state.value.steerQueued)
        assertEquals("", model.state.value.steerDraft)
        assertFalse(model.state.value.steerInFlight)
    }

    @Test
    fun steerNotAcceptingPreservesRecoveryItem() = withModel { model, chat, _, steer, _ ->
        steer.result = RunSteerResult.NotAccepting(runId)
        model.onDraftChange("hi")
        model.send()
        chat.enqueue(ChatTurnEvent.RunStarted(runId))
        model.onSteerDraftChange("Kurs")
        model.steer()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("Kurs"), model.state.value.steerRecovery)
        assertEquals("", model.state.value.steerDraft)
        assertFalse(model.state.value.steerQueued)
    }

    @Test
    fun steerFailureKeepsTextForRetry() = withModel { model, chat, _, steer, _ ->
        steer.result = RunSteerResult.Failure(RunSteerError.Network)
        model.onDraftChange("hi")
        model.send()
        chat.enqueue(ChatTurnEvent.RunStarted(runId))
        model.onSteerDraftChange("Kurs")
        model.steer()
        testScheduler.advanceUntilIdle()
        assertTrue(model.state.value.steerFailed)
        assertEquals("Kurs", model.state.value.steerDraft)
        assertFalse(model.state.value.steerInFlight)
    }

    @Test
    fun steerDoubleTapStartsExactlyOneRequest() = withModel { model, chat, _, steer, _ ->
        model.onDraftChange("hi")
        model.send()
        chat.enqueue(ChatTurnEvent.RunStarted(runId))
        model.onSteerDraftChange("Kurs")
        model.steer()
        model.steer()
        testScheduler.advanceUntilIdle()
        assertEquals(1, steer.calls.get())
    }

    // ------------------------------------------------------------------
    // Recoverable steers
    // ------------------------------------------------------------------

    @Test
    fun turnCompletedPendingSteerBecomesRecoveryItem() = withModel { model, chat, _, _, _ ->
        model.onDraftChange("hi")
        model.send()
        chat.enqueue(ChatTurnEvent.RunStarted(runId), ChatTurnEvent.TurnCompleted(pendingSteer = "später"))
        chat.complete()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("später"), model.state.value.steerRecovery)
        assertFalse(model.state.value.turnActive)
    }

    @Test
    fun sendRecoveryAsNewMessageMovesExactlyThatItem() = withModel { model, chat, _, _, _ ->
        model.onDraftChange("hi")
        model.send()
        chat.enqueue(ChatTurnEvent.RunStarted(runId), ChatTurnEvent.TurnCompleted(pendingSteer = "item-a"))
        chat.complete()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("item-a"), model.state.value.steerRecovery)
        model.sendRecoveryAsNewMessage(0)
        assertEquals("item-a", model.state.value.draft)
        assertTrue(model.state.value.steerRecovery.isEmpty())
    }

    @Test
    fun sendRecoveryIsNoOpWhileComposerNonBlank() = withModel { model, _, _, _, _ ->
        model.onDraftChange("bestehend")
        // No recovery items yet, but the no-op guard is about the draft.
        model.sendRecoveryAsNewMessage(0)
        assertEquals("bestehend", model.state.value.draft)
    }

    // ------------------------------------------------------------------
    // Draft persistence
    // ------------------------------------------------------------------

    @Test
    fun draftChangePersistsForActiveKey() = withModel { model, _, _, _, draft ->
        model.onDraftChange("hallo welt")
        testScheduler.advanceUntilIdle()
        assertEquals("hallo welt", draft.stored(profileId, sessionId))
        assertTrue(draft.persistCalls.get() > 0)
    }

    @Test
    fun runStartedClearsStoredDraft() = withModel { model, chat, _, _, draft ->
        model.onDraftChange("hallo")
        model.send()
        chat.enqueue(ChatTurnEvent.RunStarted(runId))
        testScheduler.advanceUntilIdle()
        assertTrue(draft.clearCalls.get() > 0)
        assertNull(draft.stored(profileId, sessionId))
    }

    @Test
    fun restoreDraftAppliesOnlyWithoutLocalIntent() = withModel { model, _, _, _, draft ->
        draft.seed(profileId, sessionId, "aus der disk")
        // Generation is zero (no local intent): the stored draft applies.
        model.restoreDraft()
        testScheduler.advanceUntilIdle()
        assertEquals("aus der disk", model.state.value.draft)
    }

    @Test
    fun restoreDraftDoesNotOverwriteNewerLocalDraft() = withModel { model, _, _, _, draft ->
        draft.seed(profileId, sessionId, "aus der disk")
        model.onDraftChange("neu getippt")
        model.restoreDraft()
        testScheduler.advanceUntilIdle()
        assertEquals("neu getippt", model.state.value.draft)
    }

    @Test
    fun draftChangeIsIgnoredWhileTurnActive() = withModel { model, chat, _, _, _ ->
        model.onDraftChange("hi")
        model.send()
        testScheduler.advanceUntilIdle()
        model.onDraftChange("sollte ignoriert werden")
        assertEquals("hi", model.state.value.draft)
    }

    // ------------------------------------------------------------------
    // Back
    // ------------------------------------------------------------------

    @Test
    fun backWhenIdleNavigatesImmediately() {
        var backCalls = 0
        withModel(onBack = { backCalls++ }) { model, _, _, _, _ ->
            model.handleBack()
            testScheduler.advanceUntilIdle()
            assertEquals(1, backCalls)
        }
    }

    @Test
    fun backDuringActiveTurnCancelsAndNavigatesExactlyOnce() {
        var backCalls = 0
        withModel(onBack = { backCalls++ }) { model, chat, _, _, _ ->
            model.onDraftChange("hi")
            model.send()
            assertTrue(chat.opened.await(1, TimeUnit.SECONDS))
            model.handleBack()
            testScheduler.advanceUntilIdle()
            // onBack called exactly once, collector cancelled, state reset.
            assertEquals(1, backCalls)
            assertTrue(chat.canceled.get())
            assertFalse(model.state.value.turnActive)
        }
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    @Test
    fun closeCancelsRunningCollector() = withModel { model, chat, _, _, _ ->
        model.onDraftChange("hi")
        model.send()
        assertTrue(chat.opened.await(1, TimeUnit.SECONDS))
        // Leave the screen while the turn is still running.
        model.close()
        testScheduler.advanceUntilIdle()
        assertTrue(chat.canceled.get())
    }

    // ------------------------------------------------------------------
    // Fakes
    // ------------------------------------------------------------------

    /** Synthetic streaming fake: [streamTurn] is latched, events enqueued, closed by the test. */
    private class FakeTurnSource : StreamingChatSource {
        val calls = AtomicInteger()
        val opened = CountDownLatch(1)
        val completed = AtomicBoolean(false)
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
                if (cause is CancellationException) canceled.set(true) else completed.set(true)
            }
        }

        fun enqueue(vararg events: ChatTurnEvent) {
            active?.let { channel -> events.forEach { channel.trySend(it) } }
        }

        fun complete() {
            active?.close()
        }
    }

    /** Synthetic stop fake: returns a pre-set result; records calls and parameters. */
    private class FakeStopRequester : RunStopRequester {
        val calls = AtomicInteger()
        val started = CountDownLatch(1)
        @Volatile
        var result: RunStopResult = RunStopResult.Accepted("run_" + "a".repeat(32))

        override suspend fun stopRun(profileId: String, runId: String): RunStopResult {
            calls.incrementAndGet()
            started.countDown()
            return result
        }
    }

    /** Synthetic steer fake: returns a pre-set result; records calls and parameters. */
    private class FakeSteerRequester : RunSteerRequester {
        val calls = AtomicInteger()
        val started = CountDownLatch(1)
        @Volatile
        var text: String? = null
        @Volatile
        var result: RunSteerResult = RunSteerResult.Queued("run_" + "a".repeat(32))

        override suspend fun steerRun(profileId: String, runId: String, text: String): RunSteerResult {
            calls.incrementAndGet()
            this.text = text
            started.countDown()
            return result
        }
    }

    /** In-memory draft repository: synchronous writes, records call counts. */
    private class FakeDraftRepository : ChatDraftRepository {
        private val map = java.util.concurrent.ConcurrentHashMap<String, String>()
        val persistCalls = AtomicInteger()
        val clearCalls = AtomicInteger()
        val flushCalls = AtomicInteger()

        private fun key(profileId: String, sessionId: String) = "$profileId/$sessionId"

        override suspend fun loadDraft(profileId: String, sessionId: String): String? =
            map[key(profileId, sessionId)]

        override fun persistDraft(profileId: String, sessionId: String, text: String) {
            persistCalls.incrementAndGet()
            if (text.isEmpty()) map.remove(key(profileId, sessionId))
            else map[key(profileId, sessionId)] = text
        }

        override fun clearDraft(profileId: String, sessionId: String) {
            clearCalls.incrementAndGet()
            map.remove(key(profileId, sessionId))
        }

        override fun clearProfile(profileId: String) {
            map.keys.removeAll { it.startsWith("$profileId/") }
        }

        override fun flushPendingDraft() {
            flushCalls.incrementAndGet()
        }

        fun seed(profileId: String, sessionId: String, text: String) {
            map[key(profileId, sessionId)] = text
        }

        fun stored(profileId: String, sessionId: String): String? = map[key(profileId, sessionId)]
    }
}
