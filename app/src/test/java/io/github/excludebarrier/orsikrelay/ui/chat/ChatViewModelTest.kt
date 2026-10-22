package io.github.excludebarrier.orsikrelay.ui.chat

import io.github.excludebarrier.orsikrelay.data.chat.ChatAttachmentDraftHolder
import io.github.excludebarrier.orsikrelay.data.chat.ChatAttachmentMetadata
import io.github.excludebarrier.orsikrelay.data.chat.ChatAttachmentReadResult
import io.github.excludebarrier.orsikrelay.data.chat.ChatAttachmentSource
import io.github.excludebarrier.orsikrelay.data.chat.NoopChatAttachmentSource
import io.github.excludebarrier.orsikrelay.data.chat.ChatDraftRepository
import io.github.excludebarrier.orsikrelay.domain.chat.ChatAttachmentError
import io.github.excludebarrier.orsikrelay.domain.chat.ChatAttachmentRules
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
import io.github.excludebarrier.orsikrelay.domain.run.RunForegroundServiceStarter
import io.github.excludebarrier.orsikrelay.domain.run.RunLease
import io.github.excludebarrier.orsikrelay.domain.run.RunLeaseStore
import io.github.excludebarrier.orsikrelay.domain.run.RunSessionController
import io.github.excludebarrier.orsikrelay.domain.run.RunSteerError
import io.github.excludebarrier.orsikrelay.domain.run.RunSteerRequester
import io.github.excludebarrier.orsikrelay.domain.run.RunSteerResult
import io.github.excludebarrier.orsikrelay.domain.run.RunStopError
import io.github.excludebarrier.orsikrelay.domain.run.RunStopRequester
import io.github.excludebarrier.orsikrelay.domain.run.RunStopResult
import io.github.excludebarrier.orsikrelay.domain.run.NoOpRunStatusRequester
import io.github.excludebarrier.orsikrelay.domain.run.RunServerStatus
import io.github.excludebarrier.orsikrelay.domain.run.LostRun
import io.github.excludebarrier.orsikrelay.domain.run.RunResolveError
import io.github.excludebarrier.orsikrelay.domain.run.RunResolveResult
import io.github.excludebarrier.orsikrelay.domain.run.NoOpRunUnknownResolver
import io.github.excludebarrier.orsikrelay.domain.run.RunUnknownResolver
import io.github.excludebarrier.orsikrelay.domain.run.RunStatusError
import io.github.excludebarrier.orsikrelay.domain.run.RunStatusRequester
import io.github.excludebarrier.orsikrelay.domain.run.RunStatusResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertNotNull
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * JVM unit tests for the [ChatViewModel] turn state machine.
 *
 * These tests prove the verified invariants WITHOUT a device: history
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

    /** The controller of the last built model (teardown + assertions). */
    @Volatile
    private var lastController: RunSessionController? = null

    /**
     * The attachment holder is process-scoped: drop every key this suite
     * uses so no test can rehydrate another test's picked attachment.
     */
    @Before
    fun clearAttachmentHolderForTestIsolation() {
        ChatAttachmentDraftHolder.clear(profileId, sessionId)
        ChatAttachmentDraftHolder.clear(profileId, "other-chat")
    }

    private fun readyHistory(vararg messages: HermesChatMessage): MessageHistorySource =
        MessageHistorySource { _, _ ->
            MessageHistoryResult.Success(MessagePage(messages.toList()))
        }

    private fun msg(id: Long, role: MessageRole, content: String): HermesChatMessage =
        HermesChatMessage(id = id, role = role, content = content, timestampSeconds = null, toolName = null)

    private fun page(
        messages: List<HermesChatMessage>,
        limit: Int,
        returned: Int
    ): MessagePage = MessagePage(
        messages = messages,
        limit = limit,
        returned = returned
    )

    private fun build(
        scheduler: kotlinx.coroutines.test.TestCoroutineScheduler,
        history: MessageHistorySource = readyHistory(),
        chat: StreamingChatSource = FakeTurnSource(),
        stop: RunStopRequester = FakeStopRequester(),
        steer: RunSteerRequester = FakeSteerRequester(),
        status: RunStatusRequester = NoOpRunStatusRequester,
        resolver: RunUnknownResolver = NoOpRunUnknownResolver,
        draft: ChatDraftRepository = FakeDraftRepository(),
        serviceStarter: RunForegroundServiceStarter = FakeRunServiceStarter(),
        attachmentSource: ChatAttachmentSource = NoopChatAttachmentSource,
        onBack: () -> Unit = {},
        // An EXISTING controller simulates a re-created screen that
        // re-attaches to the same app-level run.
        controller: RunSessionController? = null,
        sessionKey: String = sessionId,
        dispatcher: CoroutineDispatcher = UnconfinedTestDispatcher(scheduler),
        ioDispatcher: CoroutineDispatcher = UnconfinedTestDispatcher(scheduler)
    ): ChatViewModel {
        val resolvedController = controller ?: RunSessionController(
            chatSource = chat,
            serviceStarter = serviceStarter,
            notificationCoordinator = NoOpNotificationCoordinator,
            dispatcher = UnconfinedTestDispatcher(scheduler)
        )
        lastController = resolvedController
        return ChatViewModel(
            historySource = history,
            runController = resolvedController,
            runStopRequester = stop,
            runSteerRequester = steer,
            runStatusRequester = status,
            runUnknownResolver = resolver,
            chatDraftRepository = draft,
            attachmentSource = attachmentSource,
            profileId = profileId,
            sessionId = sessionKey,
            onBack = onBack,
            toolActivityLabel = "Tool ist aktiv…",
            namedToolActivityLabel = "Werkzeug %s ist aktiv…",
            dispatcher = dispatcher,
            ioDispatcher = ioDispatcher
        )
    }

    /** Creates the model and loads (empty) history so the composer is enabled. */
    private fun withModel(
        history: MessageHistorySource = readyHistory(),
        onBack: () -> Unit = {},
        attachmentSource: ChatAttachmentSource = NoopChatAttachmentSource,
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
        val model = build(this.testScheduler, history = history, chat = chat, stop = stop, steer = steer, draft = draft, onBack = onBack, attachmentSource = attachmentSource)
        model.loadHistory()
        testScheduler.advanceUntilIdle()
        block(model, chat, stop, steer, draft)
        model.close()
        lastController?.close()
        lastController = null
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

    // ------------------------------------------------------------------
    // Older history pages
    // ------------------------------------------------------------------

    @Test
    fun aFullInitialPageEnablesOlderLoadingAndAShortPageDoesNot() = runTest {
        val full = build(
            testScheduler,
            history = MessageHistorySource { _, _ ->
                MessageHistoryResult.Success(
                    page(listOf(msg(1, MessageRole.USER, "eins")), limit = 1, returned = 1)
                )
            }
        )
        full.loadHistory()
        testScheduler.advanceUntilIdle()
        assertTrue(full.state.value.canLoadOlder)
        assertFalse(full.state.value.loadingOlder)
        full.close()
        lastController?.close()

        val short = build(
            testScheduler,
            history = MessageHistorySource { _, _ ->
                MessageHistoryResult.Success(
                    page(listOf(msg(1, MessageRole.USER, "eins")), limit = 2, returned = 1)
                )
            }
        )
        short.loadHistory()
        testScheduler.advanceUntilIdle()
        assertFalse(short.state.value.canLoadOlder)
        short.close()
        lastController?.close()
    }

    @Test
    fun olderPageIsPrependedInServerOrderUsingTheLoadedServerRowCount() = runTest {
        val offsets = mutableListOf<Int>()
        val source = object : MessageHistorySource {
            override fun loadHistory(profileId: String, sessionId: String) =
                MessageHistoryResult.Success(
                    page(
                        listOf(
                            msg(3, MessageRole.USER, "drei"),
                            msg(4, MessageRole.ASSISTANT, "vier")
                        ),
                        limit = 2,
                        returned = 2
                    )
                )

            override fun loadOlderHistory(
                profileId: String,
                sessionId: String,
                offset: Int
            ): MessageHistoryResult {
                offsets += offset
                return MessageHistoryResult.Success(
                    page(
                        listOf(
                            msg(1, MessageRole.USER, "eins"),
                            msg(2, MessageRole.ASSISTANT, "zwei")
                        ),
                        limit = 2,
                        returned = 1
                    )
                )
            }
        }
        val model = build(testScheduler, history = source)
        model.loadHistory()
        testScheduler.advanceUntilIdle()

        model.loadOlderMessages()
        testScheduler.advanceUntilIdle()

        assertEquals(listOf(2), offsets)
        val state = model.state.value
        assertEquals(
            listOf(1L, 2L, 3L, 4L),
            (state.history as MessageHistoryState.Ready).messages.map { it.id }
        )
        assertFalse(state.canLoadOlder)
        assertFalse(state.loadingOlder)
        assertNull(state.olderLoadError)
        model.close()
        lastController?.close()
    }

    @Test
    fun aDuplicateOnlyOlderPageStopsPagingAndKeepsTheHistory() = runTest {
        val source = object : MessageHistorySource {
            override fun loadHistory(profileId: String, sessionId: String) =
                MessageHistoryResult.Success(
                    page(
                        listOf(
                            msg(3, MessageRole.USER, "drei"),
                            msg(4, MessageRole.ASSISTANT, "vier")
                        ),
                        limit = 2,
                        returned = 2
                    )
                )

            override fun loadOlderHistory(
                profileId: String,
                sessionId: String,
                offset: Int
            ) = MessageHistoryResult.Success(
                page(
                    listOf(
                        msg(3, MessageRole.USER, "drei"),
                        msg(4, MessageRole.ASSISTANT, "vier")
                    ),
                    limit = 2,
                    returned = 2
                )
            )
        }
        val model = build(testScheduler, history = source)
        model.loadHistory()
        testScheduler.advanceUntilIdle()

        model.loadOlderMessages()
        testScheduler.advanceUntilIdle()

        val state = model.state.value
        assertEquals(
            listOf(3L, 4L),
            (state.history as MessageHistoryState.Ready).messages.map { it.id }
        )
        assertFalse(state.canLoadOlder)
        assertNull(state.olderLoadError)
        model.close()
        lastController?.close()
    }

    @Test
    fun anOlderFailureLeavesTheMainHistoryIntactAndAllowsAManualRetry() = runTest {
        var olderCalls = 0
        val source = object : MessageHistorySource {
            override fun loadHistory(profileId: String, sessionId: String) =
                MessageHistoryResult.Success(
                    page(
                        listOf(
                            msg(3, MessageRole.USER, "drei"),
                            msg(4, MessageRole.ASSISTANT, "vier")
                        ),
                        limit = 2,
                        returned = 2
                    )
                )

            override fun loadOlderHistory(
                profileId: String,
                sessionId: String,
                offset: Int
            ): MessageHistoryResult {
                olderCalls += 1
                return if (olderCalls == 1) {
                    MessageHistoryResult.Failure(MessageHistoryError.RateLimited)
                } else {
                    MessageHistoryResult.Success(
                        page(listOf(msg(1, MessageRole.USER, "eins")), limit = 2, returned = 1)
                    )
                }
            }
        }
        val model = build(testScheduler, history = source)
        model.loadHistory()
        testScheduler.advanceUntilIdle()

        model.loadOlderMessages()
        testScheduler.advanceUntilIdle()
        val failed = model.state.value
        assertEquals(MessageHistoryError.RateLimited, failed.olderLoadError)
        assertEquals(
            listOf(3L, 4L),
            (failed.history as MessageHistoryState.Ready).messages.map { it.id }
        )
        assertTrue(failed.canLoadOlder)
        assertNull(failed.turnFailure)

        model.loadOlderMessages()
        testScheduler.advanceUntilIdle()
        val retried = model.state.value
        assertNull(retried.olderLoadError)
        assertEquals(
            listOf(1L, 3L, 4L),
            (retried.history as MessageHistoryState.Ready).messages.map { it.id }
        )
        assertFalse(retried.canLoadOlder)
        assertEquals(2, olderCalls)
        model.close()
        lastController?.close()
    }

    @Test
    fun onlyOneOlderLoadRunsAtATimeAndLoadingOlderToggles() = runTest {
        var olderCalls = 0
        val source = object : MessageHistorySource {
            override fun loadHistory(profileId: String, sessionId: String) =
                MessageHistoryResult.Success(
                    page(listOf(msg(3, MessageRole.USER, "drei")), limit = 1, returned = 1)
                )

            override fun loadOlderHistory(
                profileId: String,
                sessionId: String,
                offset: Int
            ): MessageHistoryResult {
                olderCalls += 1
                return MessageHistoryResult.Success(
                    page(listOf(msg(1, MessageRole.USER, "eins")), limit = 1, returned = 0)
                )
            }
        }
        val io = StandardTestDispatcher(testScheduler)
        val model = build(
            testScheduler,
            history = source,
            dispatcher = StandardTestDispatcher(testScheduler),
            ioDispatcher = io
        )
        model.loadHistory()
        testScheduler.advanceUntilIdle()
        assertTrue(model.state.value.canLoadOlder)

        val ready = model.state.value
        assertFalse(ready.loadingOlder)
        assertNull(ready.olderLoadError)

        model.loadOlderMessages()
        val loading = model.state.value
        assertTrue(loading.loadingOlder)
        assertTrue(loading.canLoadOlder)

        // A second request while one is in flight is ignored.
        model.loadOlderMessages()
        testScheduler.advanceUntilIdle()

        val done = model.state.value
        assertFalse(done.loadingOlder)
        assertFalse(done.canLoadOlder)
        assertNull(done.olderLoadError)
        assertEquals(
            listOf(1L, 3L),
            (done.history as MessageHistoryState.Ready).messages.map { it.id }
        )
        assertEquals(1, olderCalls)
        model.close()
        lastController?.close()
    }

    @Test
    fun aNewHistoryLoadDiscardsAnInFlightOlderResult() = runTest {
        val source = object : MessageHistorySource {
            override fun loadHistory(profileId: String, sessionId: String) =
                MessageHistoryResult.Success(
                    page(listOf(msg(3, MessageRole.USER, "drei")), limit = 1, returned = 1)
                )

            override fun loadOlderHistory(
                profileId: String,
                sessionId: String,
                offset: Int
            ) = MessageHistoryResult.Success(
                page(listOf(msg(1, MessageRole.USER, "eins")), limit = 1, returned = 1)
            )
        }
        val model = build(
            testScheduler,
            history = source,
            ioDispatcher = StandardTestDispatcher(testScheduler)
        )
        model.loadHistory()
        testScheduler.advanceUntilIdle()

        model.loadOlderMessages()
        model.loadHistory()
        testScheduler.advanceUntilIdle()

        val state = model.state.value
        assertEquals(
            listOf(3L),
            (state.history as MessageHistoryState.Ready).messages.map { it.id }
        )
        assertTrue(state.canLoadOlder)
        model.close()
        lastController?.close()
    }

    @Test
    fun closingTheSessionCancelsAnInFlightOlderLoad() = runTest {
        var olderCalls = 0
        val source = object : MessageHistorySource {
            override fun loadHistory(profileId: String, sessionId: String) =
                MessageHistoryResult.Success(
                    page(listOf(msg(3, MessageRole.USER, "drei")), limit = 1, returned = 1)
                )

            override fun loadOlderHistory(
                profileId: String,
                sessionId: String,
                offset: Int
            ): MessageHistoryResult {
                olderCalls += 1
                return MessageHistoryResult.Success(
                    page(listOf(msg(1, MessageRole.USER, "eins")), limit = 1, returned = 0)
                )
            }
        }
        val model = build(
            testScheduler,
            history = source,
            ioDispatcher = StandardTestDispatcher(testScheduler)
        )
        model.loadHistory()
        testScheduler.advanceUntilIdle()
        model.loadOlderMessages()
        model.close()
        testScheduler.advanceUntilIdle()

        assertEquals(0, olderCalls)
        assertEquals(
            listOf(3L),
            (model.state.value.history as MessageHistoryState.Ready).messages.map { it.id }
        )
        lastController?.close()
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
    fun turnActivityFollowsReasoningToolsAndStreamedText() = withModel { model, chat, _, _, _ ->
        model.onDraftChange("denk nach")
        model.send()
        testScheduler.advanceUntilIdle()
        assertEquals(TurnActivity.Working, model.state.value.turnActivity)

        chat.enqueue(
            ChatTurnEvent.RunStarted(runId),
            ChatTurnEvent.AssistantStarted,
            ChatTurnEvent.Reasoning(active = true)
        )
        testScheduler.advanceUntilIdle()
        assertEquals(TurnActivity.Thinking, model.state.value.turnActivity)

        chat.enqueue(ChatTurnEvent.Reasoning(active = false))
        testScheduler.advanceUntilIdle()
        assertEquals(TurnActivity.Working, model.state.value.turnActivity)

        chat.enqueue(ChatTurnEvent.AssistantDelta("Antwort"))
        testScheduler.advanceUntilIdle()
        assertNull("streamed text is the visible progress", model.state.value.turnActivity)

        chat.enqueue(ChatTurnEvent.ToolActivity())
        testScheduler.advanceUntilIdle()
        assertEquals(TurnActivity.Tool, model.state.value.turnActivity)

        chat.enqueue(ChatTurnEvent.AssistantCompleted("Antwort"), ChatTurnEvent.TurnCompleted())
        chat.complete()
        testScheduler.advanceUntilIdle()
        assertNull(model.state.value.turnActivity)
    }

    @Test
    fun streamEndingWithoutTerminalEventBecomesUncertainAndKeepsOwnership() = withModel { model, chat, _, _, _ ->
        model.onDraftChange("hi")
        model.send()
        testScheduler.advanceUntilIdle()
        // The flow closes with NO terminal event and no confirmed start: the
        // POST may have been accepted, so the ownership stays uncertain
        // instead of freeing the slot on a client-side failure.
        chat.complete()
        testScheduler.advanceUntilIdle()
        assertEquals(ChatStreamError.Network, model.state.value.turnFailure)
        assertTrue(model.state.value.turnUncertain)
        assertTrue("the ownership is kept", model.state.value.turnActive)
        assertFalse(model.state.value.runStarted)
        assertEquals("no second POST", 1, chat.calls.get())
    }

    @Test
    fun failureBeforeRunStartBecomesUncertainAndKeepsDraft() = withModel { model, chat, _, _, _ ->
        model.onDraftChange("hi")
        model.send()
        testScheduler.advanceUntilIdle()
        assertEquals(1, model.state.value.localLines.size)
        chat.enqueue(ChatTurnEvent.TurnFailed(ChatStreamError.Timeout))
        chat.complete()
        testScheduler.advanceUntilIdle()
        assertEquals(ChatStreamError.Timeout, model.state.value.turnFailure)
        // Unknown outcome - the premature user line is dropped, the draft
        // is kept, and the ownership stays uncertain (no new POST).
        assertTrue(model.state.value.localLines.isEmpty())
        assertEquals("hi", model.state.value.draft)
        assertTrue(model.state.value.turnUncertain)
        assertTrue(model.state.value.turnActive)
    }

    @Test
    fun failureAfterRunStartKeepsUserLine() = withModel { model, chat, _, _, _ ->
        model.onDraftChange("hi")
        model.send()
        chat.enqueue(ChatTurnEvent.RunStarted(runId), ChatTurnEvent.TurnFailed(ChatStreamError.Network))
        chat.complete()
        testScheduler.advanceUntilIdle()
        assertEquals(ChatStreamError.Network, model.state.value.turnFailure)
        // The user line is kept: the run had a confirmed start. (`runStarted`
        // is not reset on a failed turn; only the user line matters.)
        assertTrue(model.state.value.localLines.any { it.role == MessageRole.USER })
    }

    // ------------------------------------------------------------------
    // Transient stream re-attach (StreamReconnecting)
    // ------------------------------------------------------------------

    @Test
    fun streamReconnectingKeepsTurnActiveAndSetsReconnectingStatus() = withModel { model, chat, _, _, _ ->
        model.onDraftChange("hi")
        model.send()
        chat.enqueue(ChatTurnEvent.RunStarted(runId), ChatTurnEvent.StreamReconnecting)
        testScheduler.advanceUntilIdle()
        assertTrue(model.state.value.turnActive)
        assertTrue(model.state.value.reconnecting)
        assertNull(model.state.value.turnFailure)
    }

    @Test
    fun progressAfterReconnectingClearsReconnectingStatus() = withModel { model, chat, _, _, _ ->
        model.onDraftChange("hi")
        model.send()
        chat.enqueue(
            ChatTurnEvent.RunStarted(runId),
            ChatTurnEvent.StreamReconnecting,
            ChatTurnEvent.AssistantDelta("fortsetzung")
        )
        testScheduler.advanceUntilIdle()
        assertTrue(model.state.value.turnActive)
        assertFalse(model.state.value.reconnecting)
    }

    @Test
    fun terminalAfterReconnectingSettlesTurnAndClearsStatus() = withModel { model, chat, _, _, _ ->
        model.onDraftChange("hi")
        model.send()
        chat.enqueue(
            ChatTurnEvent.RunStarted(runId),
            ChatTurnEvent.StreamReconnecting,
            ChatTurnEvent.TurnCompleted()
        )
        chat.complete()
        testScheduler.advanceUntilIdle()
        assertFalse(model.state.value.turnActive)
        assertFalse(model.state.value.reconnecting)
        assertNull(model.state.value.turnFailure)
    }

    // ------------------------------------------------------------------
    // Unknown run outcome and manual reconciliation
    // ------------------------------------------------------------------

    /** Builds a model with a controllable [FakeRunStatusRequester]. */
    private fun withStatusModel(
        stop: FakeStopRequester = FakeStopRequester(),
        status: FakeRunStatusRequester = FakeRunStatusRequester(),
        block: suspend kotlinx.coroutines.test.TestScope.(
            model: ChatViewModel,
            chat: FakeTurnSource,
            stop: FakeStopRequester,
            status: FakeRunStatusRequester
        ) -> Unit
    ) = kotlinx.coroutines.test.runTest {
        val chat = FakeTurnSource()
        val model = build(this.testScheduler, chat = chat, stop = stop, status = status)
        model.loadHistory()
        testScheduler.advanceUntilIdle()
        block(model, chat, stop, status)
        model.close()
        lastController?.close()
        lastController = null
        testScheduler.advanceUntilIdle()
    }

    /** Drives the model into the confirmed-but-uncertain state. */
    private fun kotlinx.coroutines.test.TestScope.enterUncertainRun(
        model: ChatViewModel,
        chat: FakeTurnSource
    ) {
        model.onDraftChange("hi")
        model.send()
        advanceUntilIdle()
        chat.enqueue(ChatTurnEvent.RunStarted(runId), ChatTurnEvent.TurnFailed(ChatStreamError.Network))
        advanceUntilIdle()
    }

    @Test
    fun confirmedTransportFailureEntersUncertainAndBlocksTheComposer() = withStatusModel { model, chat, stop, _ ->
        enterUncertainRun(model, chat)
        val state = model.state.value
        assertEquals(ChatStreamError.Network, state.turnFailure)
        assertTrue(state.turnUncertain)
        assertTrue("ownership is kept", state.turnActive)
        assertEquals(runId, state.boundRunId)
        assertTrue("manual reconciliation is offered", state.recoveryVisible)
        assertFalse("the stop control is not offered", state.controlsVisible)
        assertFalse(state.sendEnabled)
        // No write is issued from the hidden controls.
        model.stop()
        model.steer()
        advanceUntilIdle()
        assertEquals(0, stop.calls.get())
        assertEquals("still exactly one POST", 1, chat.calls.get())
    }

    @Test
    fun confirmedProtocolFailureEntersUncertainWithoutReleasing() = withStatusModel { model, chat, _, _ ->
        model.onDraftChange("hi")
        model.send()
        advanceUntilIdle()
        chat.enqueue(
            ChatTurnEvent.RunStarted(runId),
            ChatTurnEvent.TurnFailed(ChatStreamError.IncompatibleApi)
        )
        advanceUntilIdle()
        assertTrue(model.state.value.turnUncertain)
        assertTrue(model.state.value.turnActive)
        assertEquals(runId, model.state.value.boundRunId)
        assertTrue(model.state.value.recoveryVisible)
        assertEquals("no second POST", 1, chat.calls.get())
    }

    /** In-memory lease store simulating a lease left by a dead process. */
    private class PreloadedLeaseStore(@Volatile var current: RunLease?) : RunLeaseStore {
        override fun load(): RunLease? = current
        override fun save(lease: RunLease) { current = lease }
        override fun clear() { current = null }
    }

    @Test
    fun screenCreatedBeforeTheLeaseRestoreShowsTheRestoredRunAsUncertain() = runTest {
        val chat = FakeTurnSource()
        val status = FakeRunStatusRequester()
        val leaseStore = PreloadedLeaseStore(RunLease(profileId, sessionId, runId))
        val controller = RunSessionController(
            chatSource = chat,
            serviceStarter = FakeRunServiceStarter(),
            notificationCoordinator = NoOpNotificationCoordinator,
            dispatcher = UnconfinedTestDispatcher(testScheduler),
            // The restore read is still pending when the screen is created
            // (Android restored the chat destination after a process death).
            ioDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler),
            leaseStore = leaseStore
        )
        lastController = controller
        val model = build(testScheduler, chat = chat, status = status, controller = controller)
        assertFalse(model.state.value.turnUncertain)
        advanceUntilIdle()

        val state = model.state.value
        assertTrue(state.turnUncertain)
        assertTrue("ownership is kept", state.turnActive)
        assertEquals(runId, state.boundRunId)
        assertTrue("manual reconciliation is offered", state.recoveryVisible)
        model.onDraftChange("neu")
        assertFalse("no second POST after a process death", model.state.value.sendEnabled)

        status.result = RunStatusResult.Known(RunServerStatus.COMPLETED)
        model.reconcileUncertainRun()
        advanceUntilIdle()
        assertEquals(runId, status.lastRunId)
        assertFalse(model.state.value.turnActive)
        assertNull(controller.activeRun.value)
        assertNull("the authoritative terminal clears the lease", leaseStore.current)
        assertEquals("no chat POST at all", 0, chat.calls.get())
        model.close()
        controller.close()
        lastController = null
    }

    @Test
    fun reconcileWithTerminalServerStatusReleasesAndReloadsHistory() = withStatusModel { model, chat, _, status ->
        enterUncertainRun(model, chat)
        status.result = RunStatusResult.Known(RunServerStatus.COMPLETED)
        model.reconcileUncertainRun()
        advanceUntilIdle()
        assertEquals(1, status.calls.get())
        assertEquals(runId, status.lastRunId)
        assertFalse(model.state.value.turnUncertain)
        assertFalse(model.state.value.turnActive)
        assertNull(model.state.value.boundRunId)
        assertNull(model.state.value.turnFailure)
        // A new, deliberate message is possible again.
        model.onDraftChange("weiter")
        assertTrue(model.state.value.sendEnabled)
    }

    @Test
    fun reconcileWithStillActiveServerStatusKeepsTheOwnership() = withStatusModel { model, chat, _, status ->
        enterUncertainRun(model, chat)
        status.result = RunStatusResult.Known(RunServerStatus.RUNNING)
        model.reconcileUncertainRun()
        advanceUntilIdle()
        assertEquals(1, status.calls.get())
        assertTrue(model.state.value.turnUncertain)
        assertTrue(model.state.value.turnActive)
        assertTrue(model.state.value.reconcileStillActive)
        assertEquals(runId, model.state.value.boundRunId)
        assertEquals("no second POST", 1, chat.calls.get())
    }

    @Test
    fun reconcileWithUnreadableStatusKeepsTheOwnership() = withStatusModel { model, chat, _, status ->
        enterUncertainRun(model, chat)
        status.result = RunStatusResult.Failure(RunStatusError.Network)
        model.reconcileUncertainRun()
        advanceUntilIdle()
        assertEquals(1, status.calls.get())
        assertTrue(model.state.value.turnUncertain)
        assertTrue(model.state.value.reconcileFailed)
        assertTrue(model.state.value.turnActive)
    }

    @Test
    fun reconcileWithARunTheServerNoLongerKnowsReleasesAndReloadsHistory() = withStatusModel { model, chat, _, status ->
        enterUncertainRun(model, chat)
        // Hermes forgets finished runs after a while and sweeps orphaned ones:
        // a confirmed 404 for our own run means it is no longer running.
        status.result = RunStatusResult.Failure(RunStatusError.NotFound)
        model.reconcileUncertainRun()
        advanceUntilIdle()
        assertEquals(1, status.calls.get())
        assertFalse(model.state.value.turnUncertain)
        assertFalse(model.state.value.turnActive)
        assertFalse(model.state.value.reconcileFailed)
        assertNull(model.state.value.boundRunId)
        assertEquals("no second POST", 1, chat.calls.get())
        model.onDraftChange("weiter")
        assertTrue(model.state.value.sendEnabled)
    }

    /** Records every resolve-unknown call and answers with [result]. */
    private class FakeResolver(var result: RunResolveResult = RunResolveResult.Resolved) : RunUnknownResolver {
        val calls = mutableListOf<Pair<String, LostRun>>()
        override suspend fun resolve(profileId: String, runId: String, lostRun: LostRun): RunResolveResult {
            calls += runId to lostRun
            return result
        }
    }

    private fun withResolverModel(
        resolver: FakeResolver,
        status: FakeRunStatusRequester = FakeRunStatusRequester(),
        block: suspend kotlinx.coroutines.test.TestScope.(ChatViewModel, FakeTurnSource, FakeRunStatusRequester) -> Unit
    ) = kotlinx.coroutines.test.runTest {
        val chat = FakeTurnSource()
        val model = build(this.testScheduler, chat = chat, status = status, resolver = resolver)
        model.loadHistory()
        testScheduler.advanceUntilIdle()
        block(model, chat, status)
        model.close()
        lastController?.close()
        lastController = null
        testScheduler.advanceUntilIdle()
    }

    @Test
    fun aLostRunIsOfferedForClosingAndNothingHappensWithoutTheTap() {
        val resolver = FakeResolver()
        withResolverModel(resolver) { model, chat, status ->
            enterUncertainRun(model, chat)
            status.result = RunStatusResult.Known(RunServerStatus.UNKNOWN, LostRun("adm_1", 3))
            model.reconcileUncertainRun()
            advanceUntilIdle()

            assertEquals(LostRun("adm_1", 3), model.state.value.lostRun)
            assertFalse(model.state.value.reconcileStillActive)
            assertTrue("still blocked", model.state.value.turnUncertain)
            assertTrue("no acknowledgement on its own", resolver.calls.isEmpty())
        }
    }

    @Test
    fun closingALostRunFreesTheChatWithoutANewMessage() {
        val resolver = FakeResolver()
        withResolverModel(resolver) { model, chat, status ->
            enterUncertainRun(model, chat)
            status.result = RunStatusResult.Known(RunServerStatus.UNKNOWN, LostRun("adm_1", 3))
            model.reconcileUncertainRun()
            advanceUntilIdle()
            model.resolveLostRun()
            advanceUntilIdle()

            assertEquals(listOf(runId to LostRun("adm_1", 3)), resolver.calls)
            assertFalse(model.state.value.turnUncertain)
            assertFalse(model.state.value.turnActive)
            assertNull(model.state.value.lostRun)
            assertEquals("no second POST", 1, chat.calls.get())
            model.onDraftChange("weiter")
            assertTrue(model.state.value.sendEnabled)
        }
    }

    @Test
    fun aStaleOrFailedCloseKeepsTheChatBlocked() {
        val resolver = FakeResolver(RunResolveResult.Failure(RunResolveError.Stale))
        withResolverModel(resolver) { model, chat, status ->
            enterUncertainRun(model, chat)
            status.result = RunStatusResult.Known(RunServerStatus.UNKNOWN, LostRun("adm_1", 3))
            model.reconcileUncertainRun()
            advanceUntilIdle()
            model.resolveLostRun()
            advanceUntilIdle()
            assertTrue(model.state.value.turnUncertain)
            assertNull("the stale key is dropped; check again", model.state.value.lostRun)
            assertFalse(model.state.value.reconcileFailed)

            resolver.result = RunResolveResult.Failure(RunResolveError.Failed)
            model.reconcileUncertainRun()
            advanceUntilIdle()
            model.resolveLostRun()
            advanceUntilIdle()
            assertTrue(model.state.value.turnUncertain)
            assertTrue(model.state.value.reconcileFailed)
            assertEquals("one tap, one request", 2, resolver.calls.size)
        }
    }

    @Test
    fun anUnconfirmedSendFailureCanBeReleasedInTheAppAndKeepsTheDraft() = withModel { model, chat, _, _, _ ->
        model.onDraftChange("hi")
        model.send()
        advanceUntilIdle()
        chat.enqueue(ChatTurnEvent.TurnFailed(ChatStreamError.Network))
        advanceUntilIdle()
        assertTrue(model.state.value.turnUncertain)
        assertTrue("no run id: only the release is offered", model.state.value.releaseVisible)

        model.releaseUncertainLocally()
        advanceUntilIdle()

        assertFalse(model.state.value.turnUncertain)
        assertFalse(model.state.value.turnActive)
        assertEquals("nothing is sent again", 1, chat.calls.get())
    }

    @Test
    fun aConfirmedRunIsReleasableOnlyAfterItsStatusCouldNotBeRead() = withStatusModel { model, chat, _, status ->
        enterUncertainRun(model, chat)
        assertFalse("a readable run is checked first", model.state.value.releaseVisible)
        status.result = RunStatusResult.Failure(RunStatusError.InvalidCredentials)
        model.reconcileUncertainRun()
        advanceUntilIdle()
        assertTrue(model.state.value.releaseVisible)
        model.releaseUncertainLocally()
        advanceUntilIdle()
        assertFalse(model.state.value.turnUncertain)
    }

    @Test
    fun anUnknownStatusWithoutAKeySaysSoAndNeverClaimsTheRunIsActive() {
        withResolverModel(FakeResolver()) { model, chat, status ->
            enterUncertainRun(model, chat)
            status.result = RunStatusResult.Known(RunServerStatus.UNKNOWN, null)
            model.reconcileUncertainRun()
            advanceUntilIdle()
            assertNull(model.state.value.lostRun)
            assertTrue(model.state.value.lostRunWithoutKey)
            assertFalse(model.state.value.reconcileStillActive)
            assertTrue("still blocked, nothing sent", model.state.value.turnUncertain)
        }
    }

    @Test
    fun unconfirmedUncertainRunOffersNoReconcileAndNeverReadsStatus() = withStatusModel { model, chat, _, status ->
        model.onDraftChange("hi")
        model.send()
        advanceUntilIdle()
        // No RunStarted: the initial POST may or may not have been accepted.
        chat.enqueue(ChatTurnEvent.TurnFailed(ChatStreamError.Timeout))
        chat.complete()
        advanceUntilIdle()
        assertTrue(model.state.value.turnUncertain)
        assertNull(model.state.value.boundRunId)
        assertFalse("no run ID: no safe reconciliation", model.state.value.recoveryVisible)
        model.reconcileUncertainRun()
        advanceUntilIdle()
        assertEquals("no status read without a run ID", 0, status.calls.get())
        assertTrue(model.state.value.turnUncertain)
    }

    @Test
    fun recreatedViewModelRehydratesTheUncertainRunWithoutANewPost() =
        withRecreatedModel(
            beforeRecreation = {
                it.enqueue(ChatTurnEvent.TurnFailed(ChatStreamError.Network))
                it.complete()
            }
        ) { second, chat, _, _ ->
            val state = second.state.value
            assertTrue("the uncertain run survives the recreation", state.turnUncertain)
            assertTrue(state.turnActive)
            assertEquals(runId, state.boundRunId)
            assertTrue(state.recoveryVisible)
            assertEquals("a recreation must never start a second stream", 1, chat.calls.get())
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
    fun secondPendingApprovalIsIncompatibleApiWithoutReleasingTheRun() = withModel { model, chat, _, _, _ ->
        model.onDraftChange("hi")
        model.send()
        chat.enqueue(ChatTurnEvent.RunStarted(runId), ChatTurnEvent.ApprovalRequested(approval()))
        testScheduler.advanceUntilIdle()
        // A second request while one is still pending is a protocol
        // contradiction. It is a LOCAL contradiction, not a confirmed
        // server stop, so the possibly-live run is NOT released - the turn
        // stays active/uncertain and the confirmed binding is kept.
        chat.enqueue(ChatTurnEvent.ApprovalRequested(approval()))
        chat.complete()
        testScheduler.advanceUntilIdle()
        assertEquals(ChatStreamError.IncompatibleApi, model.state.value.turnFailure)
        assertNull(model.state.value.pendingApproval)
        assertTrue(model.state.value.turnUncertain)
        assertTrue(model.state.value.turnActive)
        assertEquals(runId, model.state.value.boundRunId)
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
    fun stopDuringReconnectingStartsExactlyOneStopRequestAndNoNewStream() =
        withModel { model, chat, stop, _, _ ->
            model.onDraftChange("hi")
            model.send()
            chat.enqueue(ChatTurnEvent.RunStarted(runId), ChatTurnEvent.StreamReconnecting)
            testScheduler.advanceUntilIdle()
            assertTrue(model.state.value.reconnecting)

            model.stop()
            testScheduler.advanceUntilIdle()

            assertEquals("exactly one stop request", 1, stop.calls.get())
            assertEquals("no new stream/POST while re-attaching", 1, chat.calls.get())
            assertTrue("an accepted stop leaves the re-attach running", model.state.value.turnActive)
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
    fun backDuringActiveTurnNavigatesOnceAndRunContinues() {
        var backCalls = 0
        withModel(onBack = { backCalls++ }) { model, chat, _, _, _ ->
            model.onDraftChange("hi")
            model.send()
            assertTrue(chat.opened.await(1, TimeUnit.SECONDS))
            model.handleBack()
            testScheduler.advanceUntilIdle()
            // Background continuity: onBack is called exactly once, but the
            // run is NOT interrupted — it is owned app-level by the controller
            // and keeps streaming (the foreground service keeps the process
            // unfrozen). The screen's own turn state stays active because the
            // (not-yet-disposed) view model is still observing the run.
            assertEquals(1, backCalls)
            assertFalse(chat.canceled.get())
            assertTrue(model.state.value.turnActive)
            assertNotNull(lastController?.activeRun?.value)
        }
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    @Test
    fun closeKeepsRunAliveOnController() = withModel { model, chat, _, _, _ ->
        model.onDraftChange("hi")
        model.send()
        assertTrue(chat.opened.await(1, TimeUnit.SECONDS))
        // Leave the screen while the turn is still running: the run is owned
        // app-level by the controller and is NOT interrupted by disposal —
        // only the view model's event observer is canceled.
        model.close()
        testScheduler.advanceUntilIdle()
        assertFalse(chat.canceled.get())
        assertNotNull(lastController?.activeRun?.value)
    }

    // ------------------------------------------------------------------
    // Attachments
    // ------------------------------------------------------------------

    @Test
    fun pickerAcceptsJpegPngAndWebpWithNormalizedMimeAndSize() {
        val picks = listOf(
            Triple("content://img/jpeg", "photo.jpg", "IMAGE/JPEG"),
            Triple("content://img/png", "bild.png", "Image/PNG"),
            Triple("content://img/webp", "bild.webp", "image/webp ")
        )
        for ((uri, name, rawMime) in picks) {
            val source = FakeAttachmentSource().apply {
                metadata = mapOf(uri to ChatAttachmentMetadata(name, rawMime, 1234L))
            }
            withModel(attachmentSource = source) { model, _, _, _, _ ->
                model.onAttachmentPicked(uri)
                testScheduler.advanceUntilIdle()
                val attachment = model.state.value.attachment
                assertNotNull(attachment)
                assertEquals(rawMime.trim().lowercase(), attachment?.mimeType)
                assertEquals(1234L, attachment?.sizeBytes)
                assertEquals(name, attachment?.displayName)
                assertEquals(uri, attachment?.contentUri)
                assertFalse(model.state.value.attachmentBusy)
                assertNull(model.state.value.attachmentError)
                // The pick is staged for recreation rehydration.
                assertEquals(attachment, ChatAttachmentDraftHolder.get(profileId, sessionId))
            }
        }
    }

    @Test
    fun unsupportedMimeTypesAreRejectedAsUnsupportedType() {
        val source = FakeAttachmentSource().apply {
            metadata = mapOf(
                "content://doc/pdf" to ChatAttachmentMetadata("doc.pdf", "application/pdf", 10L),
                "content://doc/txt" to ChatAttachmentMetadata("note.txt", "text/plain", 10L)
            )
        }
        withModel(attachmentSource = source) { model, _, _, _, _ ->
            model.onAttachmentPicked("content://doc/pdf")
            testScheduler.advanceUntilIdle()
            assertEquals(ChatAttachmentError.UnsupportedType, model.state.value.attachmentError)
            assertNull(model.state.value.attachment)
            assertFalse(model.state.value.attachmentBusy)

            model.onAttachmentPicked("content://doc/txt")
            testScheduler.advanceUntilIdle()
            assertEquals(ChatAttachmentError.UnsupportedType, model.state.value.attachmentError)
            assertNull(model.state.value.attachment)
            // A rejected pick never reaches the holder.
            assertNull(ChatAttachmentDraftHolder.get(profileId, sessionId))
        }
    }

    @Test
    fun knownOversizedImageIsRejectedAsTooLarge() {
        val source = FakeAttachmentSource().apply {
            metadata = mapOf(
                "content://big" to ChatAttachmentMetadata(
                    "big.jpg",
                    "image/jpeg",
                    ChatAttachmentRules.MAX_IMAGE_BYTES + 1
                )
            )
        }
        withModel(attachmentSource = source) { model, _, _, _, _ ->
            model.onAttachmentPicked("content://big")
            testScheduler.advanceUntilIdle()
            assertEquals(ChatAttachmentError.TooLarge, model.state.value.attachmentError)
            assertNull(model.state.value.attachment)
            assertNull(ChatAttachmentDraftHolder.get(profileId, sessionId))
        }
    }

    @Test
    fun unknownSizeIsAccepted() {
        val source = FakeAttachmentSource().apply {
            metadata = mapOf("content://cloud" to ChatAttachmentMetadata("cloud.jpg", "image/jpeg", -1L))
        }
        withModel(attachmentSource = source) { model, _, _, _, _ ->
            model.onAttachmentPicked("content://cloud")
            testScheduler.advanceUntilIdle()
            val attachment = model.state.value.attachment
            assertNotNull(attachment)
            // A non-positive provider size means "unknown", not "too large".
            assertEquals(-1L, attachment?.sizeBytes)
            assertNull(model.state.value.attachmentError)
        }
    }

    @Test
    fun metadataReadFailureSetsUnreadable() {
        val source = FakeAttachmentSource().apply {
            throwingMetadataUris = setOf("content://boom")
        }
        withModel(attachmentSource = source) { model, _, _, _, _ ->
            // A provider that yields nothing (no metadata entry)...
            model.onAttachmentPicked("content://gone")
            testScheduler.advanceUntilIdle()
            assertEquals(ChatAttachmentError.Unreadable, model.state.value.attachmentError)
            assertNull(model.state.value.attachment)
            assertFalse(model.state.value.attachmentBusy)
            assertNull(ChatAttachmentDraftHolder.get(profileId, sessionId))

            // ...and one that throws are the same closed failure at the gate.
            model.onAttachmentPicked("content://boom")
            testScheduler.advanceUntilIdle()
            assertEquals(ChatAttachmentError.Unreadable, model.state.value.attachmentError)
            assertNull(model.state.value.attachment)
        }
    }

    @Test
    fun removeAttachmentClearsStateAndHolder() {
        val source = FakeAttachmentSource().apply {
            metadata = mapOf("content://img" to ChatAttachmentMetadata("img.png", "image/png", 10L))
        }
        withModel(attachmentSource = source) { model, _, _, _, _ ->
            model.onAttachmentPicked("content://img")
            testScheduler.advanceUntilIdle()
            assertNotNull(model.state.value.attachment)
            assertNotNull(ChatAttachmentDraftHolder.get(profileId, sessionId))

            model.removeAttachment()
            testScheduler.advanceUntilIdle()
            assertNull(model.state.value.attachment)
            assertNull(model.state.value.attachmentError)
            assertNull(ChatAttachmentDraftHolder.get(profileId, sessionId))

            // Removing again is a no-op that keeps the state clean.
            model.removeAttachment()
            assertNull(model.state.value.attachment)
        }
    }

    @Test
    fun textOnlySendStartsOneRunWithoutAttachment() {
        val source = FakeAttachmentSource()
        withModel(attachmentSource = source) { model, chat, _, _, _ ->
            model.onDraftChange("hallo")
            model.send()
            testScheduler.advanceUntilIdle()
            assertEquals(1, chat.calls.get())
            // No attachment: the text-only path is used and the byte read
            // seam is never touched.
            assertEquals(0, chat.attachmentCalls.get())
            assertEquals("hallo", chat.lastMessage)
            assertEquals(0, source.dataUrlCalls.get())
            assertNull(model.state.value.attachment)
        }
    }

    @Test
    fun imageOnlySendStartsOneRunAndReadsDataUrlOnce() {
        val source = FakeAttachmentSource().apply {
            metadata = mapOf("content://solo" to ChatAttachmentMetadata("img.png", "image/png", 10L))
            dataUrls = mapOf("content://solo" to "data:image/png;base64,QUJD")
        }
        withModel(attachmentSource = source) { model, chat, _, _, _ ->
            // No text: a blank draft plus the attachment is a legitimate turn.
            model.onAttachmentPicked("content://solo")
            testScheduler.advanceUntilIdle()
            assertNotNull(model.state.value.attachment)

            model.send()
            testScheduler.advanceUntilIdle()
            assertEquals(1, chat.calls.get())
            assertEquals(1, chat.attachmentCalls.get())
            assertEquals(1, source.dataUrlCalls.get())
            assertEquals("", chat.lastMessage)
            assertEquals(listOf("data:image/png;base64,QUJD"), chat.lastImageDataUrls)
            assertTrue(model.state.value.turnActive)
        }
    }

    @Test
    fun textAndImageSendPassesTextAndOneDataUrlToController() {
        val source = FakeAttachmentSource().apply {
            metadata = mapOf("content://pair" to ChatAttachmentMetadata("img.jpg", "image/jpeg", 10L))
            dataUrls = mapOf("content://pair" to "data:image/jpeg;base64,QkVX")
        }
        withModel(attachmentSource = source) { model, chat, _, _, _ ->
            model.onAttachmentPicked("content://pair")
            testScheduler.advanceUntilIdle()
            model.onDraftChange("bild anbei")
            testScheduler.advanceUntilIdle()

            model.send()
            testScheduler.advanceUntilIdle()
            assertEquals(1, chat.calls.get())
            assertEquals(1, chat.attachmentCalls.get())
            assertEquals(1, source.dataUrlCalls.get())
            assertEquals("bild anbei", chat.lastMessage)
            assertEquals(listOf("data:image/jpeg;base64,QkVX"), chat.lastImageDataUrls)
        }
    }

    @Test
    fun sendIsBlockedWhileAttachmentIsBusy() {
        val source = FakeAttachmentSource().apply {
            metadata = mapOf("content://slow" to ChatAttachmentMetadata("slow.png", "image/png", 10L))
            metadataGate = CompletableDeferred()
        }
        withModel(attachmentSource = source) { model, chat, _, _, _ ->
            model.onAttachmentPicked("content://slow")
            // The metadata read is still in flight: the pick is busy.
            assertTrue(model.state.value.attachmentBusy)
            // The composer gate stays off for any draft while the pick is
            // in flight (a plain "hallo" alone would enable it).
            model.onDraftChange("hallo")
            assertFalse(model.state.value.sendEnabled)
            // A deliberate submit cannot start a run while the pick is
            // unreadable (blank draft: the only reachable submit shape).
            model.onDraftChange("")
            model.send()
            testScheduler.advanceUntilIdle()
            assertEquals(0, chat.calls.get())
            assertFalse(model.state.value.turnActive)

            source.metadataGate?.complete(Unit)
            testScheduler.advanceUntilIdle()
            assertFalse(model.state.value.attachmentBusy)
            assertNotNull(model.state.value.attachment)
            // The attachment alone now enables the composer.
            assertTrue(model.state.value.sendEnabled)
        }
    }

    @Test
    fun dataUrlReadFailureKeepsDraftAndAttachmentWithoutPost() {
        val source = FakeAttachmentSource().apply {
            metadata = mapOf("content://bad" to ChatAttachmentMetadata("bad.png", "image/png", 10L))
            // No data URL entry: the byte read yields nothing.
        }
        withModel(attachmentSource = source) { model, chat, _, _, _ ->
            model.onAttachmentPicked("content://bad")
            testScheduler.advanceUntilIdle()
            model.onDraftChange("bitte")
            testScheduler.advanceUntilIdle()

            model.send()
            testScheduler.advanceUntilIdle()
            assertEquals(1, source.dataUrlCalls.get())
            assertEquals(0, chat.calls.get())
            assertFalse(model.state.value.turnActive)
            assertEquals(ChatAttachmentError.Unreadable, model.state.value.attachmentError)
            // The text draft and the attachment survive for a deliberate retry.
            assertEquals("bitte", model.state.value.draft)
            assertNotNull(model.state.value.attachment)
            assertNotNull(ChatAttachmentDraftHolder.get(profileId, sessionId))
            assertTrue(model.state.value.localLines.isEmpty())

            // A throwing provider is the same closed failure: still no POST.
            source.throwingDataUrlUris = setOf("content://bad")
            model.send()
            testScheduler.advanceUntilIdle()
            assertEquals(2, source.dataUrlCalls.get())
            assertEquals(0, chat.calls.get())
            assertFalse(model.state.value.turnActive)
            assertEquals(ChatAttachmentError.Unreadable, model.state.value.attachmentError)
            assertNotNull(model.state.value.attachment)
        }
    }

    @Test
    fun tooLargeReadOutcomeAtSendSetsTooLargeErrorWithoutPost() {
        val source = FakeAttachmentSource().apply {
            metadata = mapOf("content://oversized" to ChatAttachmentMetadata("oversized.png", "image/png", 10L))
            // The closed outcome: the provider streamed more than the
            // caller's bound while never reporting a size up front.
            dataUrlResults = mapOf("content://oversized" to ChatAttachmentReadResult.TooLarge)
        }
        withModel(attachmentSource = source) { model, chat, _, _, _ ->
            model.onAttachmentPicked("content://oversized")
            testScheduler.advanceUntilIdle()
            assertNotNull(model.state.value.attachment)

            model.onDraftChange("bitte")
            model.send()
            testScheduler.advanceUntilIdle()

            // The closed TooLarge outcome maps to its own error, and the
            // over-limit attachment is never POSTed.
            assertFalse(model.state.value.turnActive)
            assertEquals(ChatAttachmentError.TooLarge, model.state.value.attachmentError)
            assertEquals(0, chat.calls.get())
            assertEquals(0, chat.attachmentCalls.get())
            assertEquals(1, source.dataUrlCalls.get())
            // The text draft and the attachment survive for a deliberate retry.
            assertEquals("bitte", model.state.value.draft)
            assertNotNull(model.state.value.attachment)
            assertTrue(model.state.value.localLines.isEmpty())
        }
    }

    @Test
    fun runStartedClearsAttachmentAndHolderEntry() {
        val source = FakeAttachmentSource().apply {
            metadata = mapOf("content://sent" to ChatAttachmentMetadata("sent.png", "image/png", 10L))
            dataUrls = mapOf("content://sent" to "data:image/png;base64,QUJD")
        }
        withModel(attachmentSource = source) { model, chat, _, _, _ ->
            model.onAttachmentPicked("content://sent")
            testScheduler.advanceUntilIdle()
            model.send()
            testScheduler.advanceUntilIdle()
            // Before the confirmed start the attachment is still staged
            // (state AND holder).
            assertNotNull(model.state.value.attachment)
            assertNotNull(ChatAttachmentDraftHolder.get(profileId, sessionId))

            chat.enqueue(ChatTurnEvent.RunStarted(runId))
            testScheduler.advanceUntilIdle()
            // The confirmed start clears the sent attachment from the
            // composer and the process-scoped rehydration entry.
            assertNull(model.state.value.attachment)
            assertNull(model.state.value.attachmentError)
            assertNull(ChatAttachmentDraftHolder.get(profileId, sessionId))
            assertTrue(model.state.value.runStarted)
            assertEquals(runId, model.state.value.boundRunId)

            // Progress after the start never resurrects it.
            chat.enqueue(ChatTurnEvent.AssistantDelta("ok"))
            testScheduler.advanceUntilIdle()
            assertNull(model.state.value.attachment)
            assertNull(ChatAttachmentDraftHolder.get(profileId, sessionId))
        }
    }

    @Test
    fun failureBeforeRunStartedKeepsAttachment() {
        val source = FakeAttachmentSource().apply {
            metadata = mapOf("content://keep" to ChatAttachmentMetadata("keep.png", "image/png", 10L))
            dataUrls = mapOf("content://keep" to "data:image/png;base64,QUJD")
        }
        withModel(attachmentSource = source) { model, chat, _, _, _ ->
            model.onAttachmentPicked("content://keep")
            testScheduler.advanceUntilIdle()
            model.onDraftChange("bitte")
            model.send()
            testScheduler.advanceUntilIdle()
            chat.enqueue(ChatTurnEvent.TurnFailed(ChatStreamError.Timeout))
            chat.complete()
            testScheduler.advanceUntilIdle()
            assertEquals(ChatStreamError.Timeout, model.state.value.turnFailure)
            // An unconfirmed timeout is an unknown outcome - the turn
            // stays active/uncertain (no new POST). The attachment (state AND
            // holder) and the text draft survive, so nothing is lost.
            assertTrue(model.state.value.turnUncertain)
            assertTrue(model.state.value.turnActive)
            assertNotNull(model.state.value.attachment)
            assertNotNull(ChatAttachmentDraftHolder.get(profileId, sessionId))
            assertEquals("bitte", model.state.value.draft)
        }
    }

    @Test
    fun anotherSessionNeverSeesThePickersDraft() {
        val source = FakeAttachmentSource().apply {
            metadata = mapOf("content://mine" to ChatAttachmentMetadata("mine.png", "image/png", 10L))
        }
        withModel(attachmentSource = source) { model, _, _, _, _ ->
            model.onAttachmentPicked("content://mine")
            testScheduler.advanceUntilIdle()
            assertNotNull(model.state.value.attachment)
            assertNotNull(ChatAttachmentDraftHolder.get(profileId, sessionId))

            // A model of ANOTHER session (same profile, same app-level
            // controller) must start without it.
            val other = build(
                testScheduler,
                controller = lastController,
                attachmentSource = source,
                sessionKey = "other-chat"
            )
            other.loadHistory()
            testScheduler.advanceUntilIdle()
            assertNull(other.state.value.attachment)
            assertNull(ChatAttachmentDraftHolder.get(profileId, "other-chat"))
            other.close()
            testScheduler.advanceUntilIdle()
            // The original chat still has its attachment.
            assertNotNull(model.state.value.attachment)
        }
    }

    @Test
    fun recreatedViewModelRehydratesThePickedAttachment() {
        val source = FakeAttachmentSource().apply {
            metadata = mapOf("content://picked" to ChatAttachmentMetadata("picked.png", "image/png", 10L))
        }
        withRecreatedModel(
            attachmentSource = source,
            beforeRecreation = {
                it.enqueue(ChatTurnEvent.TurnCompleted())
                it.complete()
            },
            actOnFirstModel = { it.onAttachmentPicked("content://picked") }
        ) { second, chat, _, _ ->
            // The re-created screen rehydrates the picked-but-unsent
            // attachment from the process-scoped holder.
            val attachment = second.state.value.attachment
            assertNotNull(attachment)
            assertEquals("content://picked", attachment?.contentUri)
            assertEquals("image/png", attachment?.mimeType)
            assertEquals("picked.png", attachment?.displayName)
            // The recreation itself never POSTs.
            assertEquals(1, chat.calls.get())
        }
    }

    // ------------------------------------------------------------------
    // Fakes
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // Activity / view-model recreation rehydration
    // ------------------------------------------------------------------

    /**
     * Runs the two-screen recreation scenario on ONE controller: the first
     * screen sends and streams, [beforeRecreation] may inject further events,
     * the first screen is disposed (the Activity recreation), and a second
     * model is built on the SAME controller (the re-created screen).
     */
    private fun withRecreatedModel(
        beforeRecreation: (FakeTurnSource) -> Unit = {},
        actOnFirstModel: (ChatViewModel) -> Unit = {},
        sessionKeyOfSecond: String = sessionId,
        historyForSecond: MessageHistorySource = readyHistory(),
        attachmentSource: ChatAttachmentSource = NoopChatAttachmentSource,
        block: suspend kotlinx.coroutines.test.TestScope.(
            second: ChatViewModel,
            chat: FakeTurnSource,
            stop: FakeStopRequester,
            controller: RunSessionController
        ) -> Unit
    ) = runTest {
        val chat = FakeTurnSource()
        val stop = FakeStopRequester()
        val controller = RunSessionController(
            chatSource = chat,
            serviceStarter = FakeRunServiceStarter(),
            notificationCoordinator = NoOpNotificationCoordinator,
            dispatcher = UnconfinedTestDispatcher(testScheduler)
        )
        lastController = controller
        val first = build(testScheduler, chat = chat, stop = stop, controller = controller, attachmentSource = attachmentSource)
        first.loadHistory()
        testScheduler.advanceUntilIdle()
        first.onDraftChange("Hallo Welt")
        first.send()
        testScheduler.advanceUntilIdle()
        chat.enqueue(ChatTurnEvent.RunStarted(runId))
        testScheduler.advanceUntilIdle()
        chat.enqueue(ChatTurnEvent.AssistantDelta("Das ist eine "))
        testScheduler.advanceUntilIdle()
        beforeRecreation(chat)
        testScheduler.advanceUntilIdle()
        actOnFirstModel(first)
        testScheduler.advanceUntilIdle()
        // The Activity is re-created: the screen's view model dies, the
        // app-level controller and its run keep going.
        first.close()
        testScheduler.advanceUntilIdle()
        val second = build(
            testScheduler,
            history = historyForSecond,
            chat = chat,
            stop = stop,
            controller = controller,
            sessionKey = sessionKeyOfSecond,
            attachmentSource = attachmentSource
        )
        second.loadHistory()
        testScheduler.advanceUntilIdle()
        block(this, second, chat, stop, controller)
        second.close()
        controller.close()
        lastController = null
        testScheduler.advanceUntilIdle()
    }

    @Test
    fun aModelWithoutAnActiveRunStartsIdle() = withModel { model, _, _, _, _ ->
        assertFalse(model.state.value.turnActive)
        assertTrue(model.state.value.localLines.isEmpty())
        assertNull(model.state.value.boundRunId)
        assertFalse(model.state.value.reconnecting)
    }

    @Test
    fun recreatedViewModelRehydratesTheRunningTurnWithoutANewPost() =
        withRecreatedModel { second, chat, _, _ ->
            val state = second.state.value
            assertTrue("the turn must survive the recreation", state.turnActive)
            assertTrue(state.runStarted)
            assertEquals(runId, state.boundRunId)
            assertTrue("the stop control must be available again", state.controlsVisible)
            assertEquals(
                "the visible turn must be restored exactly (user line + partial text)",
                listOf(MessageRole.USER to "Hallo Welt", MessageRole.ASSISTANT to "Das ist eine "),
                state.localLines.map { it.role to it.content }
            )
            assertEquals("a recreation must never start a second stream", 1, chat.calls.get())
            assertTrue(lastController?.activeRun?.value?.assistantText == "Das ist eine ")
        }

    @Test
    fun recreatedViewModelAppendsFurtherDeltasExactlyOnce() =
        withRecreatedModel { second, chat, _, _ ->
            chat.enqueue(ChatTurnEvent.AssistantDelta("bereits teilweise erzeugte Antwort."))
            testScheduler.advanceUntilIdle()
            chat.enqueue(ChatTurnEvent.AssistantCompleted("Das ist eine bereits teilweise erzeugte Antwort."))
            testScheduler.advanceUntilIdle()
            val lines = second.state.value.localLines
            assertEquals("no duplicate or lost assistant row", 2, lines.size)
            assertEquals(1, lines.count { it.role == MessageRole.ASSISTANT })
            assertEquals(
                "Das ist eine bereits teilweise erzeugte Antwort.",
                lines.last().content
            )
            assertEquals(1, chat.calls.get())
        }

    @Test
    fun recreatedViewModelKeepsTheReconnectingStatusUntilProgressArrives() = withRecreatedModel(
        beforeRecreation = { it.enqueue(ChatTurnEvent.StreamReconnecting) }
    ) { second, chat, _, _ ->
        assertTrue("reconnecting must survive the recreation", second.state.value.reconnecting)
        assertTrue(second.state.value.turnActive)
        assertEquals(runId, second.state.value.boundRunId)
        chat.enqueue(ChatTurnEvent.AssistantDelta("weiter"))
        testScheduler.advanceUntilIdle()
        assertFalse(second.state.value.reconnecting)
        assertEquals(1, chat.calls.get())
    }

    @Test
    fun recreatedViewModelShowsTheSamePendingApprovalExactlyOnce() = withRecreatedModel(
        beforeRecreation = {
            it.enqueue(
                ChatTurnEvent.ApprovalRequested(
                    ApprovalRequest(runId, requestId, ApprovalRequest.SAFE_DESCRIPTION)
                )
            )
        }
    ) { second, chat, _, controller ->
        val approval = second.state.value.pendingApproval
        assertEquals("the same request must be shown", requestId, approval?.requestId)
        assertEquals(runId, approval?.runId)
        assertEquals(1, chat.calls.get())
        // The user's decision clears it in the view model AND app-level, so
        // no re-created screen can re-show a resolved request.
        second.dismissApproval()
        testScheduler.advanceUntilIdle()
        assertNull(second.state.value.pendingApproval)
        assertNull(controller.activeRun.value?.pendingApproval)
    }

    @Test
    fun stopAfterRecreationTargetsTheSameRunAndStartsNoNewStream() =
        withRecreatedModel { second, chat, stop, _ ->
            second.stop()
            assertTrue(stop.started.await(1, TimeUnit.SECONDS))
            testScheduler.advanceUntilIdle()
            assertEquals(1, stop.calls.get())
            assertEquals("the stop must reach the same run", runId, stop.lastRunId)
            assertEquals(1, chat.calls.get())
        }

    @Test
    fun aRecreatedViewModelOfAnotherSessionNeverBindsTheRun() =
        withRecreatedModel(sessionKeyOfSecond = "other-session") { second, chat, _, _ ->
            assertFalse(
                "another session must never show this run",
                second.state.value.turnActive
            )
            assertNull(second.state.value.boundRunId)
            assertTrue(second.state.value.localLines.isEmpty())
            assertFalse(second.state.value.controlsVisible)
            assertEquals(1, chat.calls.get())
        }

    @Test
    fun aRehydratedUserLineIsNotDuplicatedOnceTheHistoryCarriesIt() =
        withRecreatedModel(
            // The server stores the user message when the run starts, so the
            // re-created screen's history already contains it.
            historyForSecond = readyHistory(
                msg(1, MessageRole.USER, "Hallo Welt")
            )
        ) { second, _, _, _ ->
            val state = second.state.value
            val timeline = buildTimeline(
                (state.history as MessageHistoryState.Ready).messages,
                state.localLines
            )
            assertEquals(
                "the user's own message must be rendered exactly once",
                1,
                timeline.count { it.role == MessageRole.USER && it.content == "Hallo Welt" }
            )
            assertEquals(
                "the rehydrated partial assistant text must stay",
                1,
                timeline.count { it.role == MessageRole.ASSISTANT }
            )
        }

    @Test
    fun recreatedViewModelRebuildsToolRowsAndKeepsTheAssistantRowLast() =
        withRecreatedModel(
            beforeRecreation = { it.enqueue(ChatTurnEvent.ToolActivity(toolName = "web_search")) }
        ) { second, chat, _, _ ->
            val roles = second.state.value.localLines.map { it.role }
            assertEquals(
                listOf(MessageRole.USER, MessageRole.TOOL, MessageRole.ASSISTANT),
                roles
            )
            assertEquals(
                "Werkzeug web_search ist aktiv…",
                second.state.value.localLines[1].content
            )
            chat.enqueue(ChatTurnEvent.AssistantDelta("mehr"))
            testScheduler.advanceUntilIdle()
            assertEquals("a delta must append, not add a row", 3, second.state.value.localLines.size)
            assertEquals(
                "Das ist eine mehr",
                second.state.value.localLines.last().content
            )
            chat.enqueue(ChatTurnEvent.AssistantCompleted("Das ist eine mehr"))
            testScheduler.advanceUntilIdle()
            assertEquals(
                "assistant.completed must replace the rehydrated row",
                3,
                second.state.value.localLines.size
            )
            assertEquals(1, second.state.value.localLines.count { it.role == MessageRole.ASSISTANT })
        }

    @Test
    fun aRehydratedTurnIsDroppedOnceTheHistoryRecordsItInFull() =
        withRecreatedModel(
            // The run finished before the re-created screen's history load
            // resolved, so the recorded turn is complete in the history.
            historyForSecond = readyHistory(
                msg(1, MessageRole.USER, "Hallo Welt"),
                msg(2, MessageRole.ASSISTANT, "Das ist eine Antwort.")
            )
        ) { second, _, _, _ ->
            assertTrue(
                "the authoritative history replaces the rehydrated turn rows",
                second.state.value.localLines.isEmpty()
            )
            val timeline = buildTimeline(
                (second.state.value.history as MessageHistoryState.Ready).messages,
                second.state.value.localLines
            )
            assertEquals(1, timeline.count { it.role == MessageRole.USER })
            assertEquals(1, timeline.count { it.role == MessageRole.ASSISTANT })
        }

    @Test
    fun recreationAfterDismissShowsNoDialog() = withRecreatedModel(
        beforeRecreation = {
            it.enqueue(
                ChatTurnEvent.ApprovalRequested(
                    ApprovalRequest(runId, requestId, ApprovalRequest.SAFE_DESCRIPTION)
                )
            )
        },
        actOnFirstModel = { it.dismissApproval() }
    ) { second, _, _, controller ->
        assertNull("an applied decision must not come back", second.state.value.pendingApproval)
        assertNull(controller.activeRun.value?.pendingApproval)
        // The run itself is untouched by the approval decision.
        assertEquals(sessionId, controller.activeRun.value?.sessionId)
        assertTrue(controller.activeRun.value != null)
    }

    @Test
    fun recreationAfterAFinishedApprovalShowsNoDialog() = withRecreatedModel(
        beforeRecreation = {
            it.enqueue(
                ChatTurnEvent.ApprovalRequested(
                    ApprovalRequest(runId, requestId, ApprovalRequest.SAFE_DESCRIPTION)
                )
            )
        },
        actOnFirstModel = { it.finishApproval() }
    ) { second, _, _, controller ->
        // The server's authoritative "not pending" answer is terminal: the
        // app-level record is gone, so a re-created screen shows no dialog.
        assertNull(second.state.value.pendingApproval)
        assertNull(controller.activeRun.value?.pendingApproval)
    }

    @Test
    fun recreationWhileAnApprovalIsStillUndecidedShowsTheSameRequest() = withRecreatedModel(
        beforeRecreation = {
            it.enqueue(
                ChatTurnEvent.ApprovalRequested(
                    ApprovalRequest(runId, requestId, ApprovalRequest.SAFE_DESCRIPTION)
                )
            )
        }
        // No decision at all: the re-created screen must show the still
        // undecided request again (the ambiguous `Failure` branch that also
        // keeps the durable record is covered by
        // ApprovalUiGateTest.ambiguousFailureNeitherReportsFinishedNorReenablesTheButtons
        // and by the real E2E G4 run).
    ) { second, _, _, controller ->
        assertEquals(requestId, second.state.value.pendingApproval?.requestId)
        assertEquals(requestId, controller.activeRun.value?.pendingApproval?.requestId)
        assertTrue(second.state.value.controlsVisible || second.state.value.turnActive)
    }

    @Test
    fun finishApprovalKeepsTheLocalDialogStateButDropsTheAppLevelRecord() =
        withModel { model, chat, _, _, _ ->
            model.onDraftChange("hi")
            model.send()
            chat.enqueue(ChatTurnEvent.RunStarted(runId))
            chat.enqueue(
                ChatTurnEvent.ApprovalRequested(
                    ApprovalRequest(runId, requestId, ApprovalRequest.SAFE_DESCRIPTION)
                )
            )
            testScheduler.advanceUntilIdle()
            assertEquals(requestId, model.state.value.pendingApproval?.requestId)
            // 409 NoPending: the authoritative terminal outcome keeps THIS
            // composition's note but drops the durable record.
            model.finishApproval()
            testScheduler.advanceUntilIdle()
            assertEquals(
                "the local composition keeps its request state",
                requestId,
                model.state.value.pendingApproval?.requestId
            )
            assertNull(lastController?.activeRun?.value?.pendingApproval)
        }

    @Test
    fun aTerminalRunIsNotRehydratedAsActive() = withRecreatedModel(
        beforeRecreation = { it.enqueue(ChatTurnEvent.TurnCompleted()) }
    ) { second, _, _, controller ->
        assertNull("no stale active run after the terminal event", controller.activeRun.value)
        assertFalse(second.state.value.turnActive)
        assertFalse(second.state.value.controlsVisible)
        assertFalse(second.state.value.reconnecting)
        assertNull(second.state.value.pendingApproval)
        assertTrue(second.state.value.localLines.isEmpty())
    }

    // ------------------------------------------------------------------
    // Global run ownership (a foreign active run rejects a second send)
    // ------------------------------------------------------------------

    @Test
    fun aForeignActiveRunRejectsTheSendAndKeepsDraftAndAttachment() {
        val attachmentSource = FakeAttachmentSource().apply {
            metadata = mapOf(
                "content://b" to ChatAttachmentMetadata("b.png", "image/png", 10L)
            )
        }
        withRecreatedModel(
            sessionKeyOfSecond = "other-chat",
            attachmentSource = attachmentSource
        ) { second, chat, _, controller ->
            second.onAttachmentPicked("content://b")
            testScheduler.advanceUntilIdle()
            assertNotNull("the attachment must be staged first", second.state.value.attachment)
            second.onDraftChange("Nachricht aus B")
            second.send()
            testScheduler.advanceUntilIdle()

            assertFalse("no second run may become active", second.state.value.turnActive)
            assertTrue("a visible conflict state is required", second.state.value.startConflict)
            assertEquals("the draft must stay exactly", "Nachricht aus B", second.state.value.draft)
            assertNotNull("the picked attachment must not be lost", second.state.value.attachment)
            assertTrue("no local user line may persist", second.state.value.localLines.isEmpty())
            assertEquals("exactly one POST (run A)", 1, chat.calls.get())
            assertTrue("run A must still own the slot", controller.activeRun.value != null)
            assertEquals(sessionId, controller.activeRun.value?.sessionId)
        }
    }

    @Test
    fun aRecreatedSameSessionModelCannotStartASecondRun() = withRecreatedModel { second, chat, _, _ ->
        // The run is still active on this session after the recreation.
        second.onDraftChange("unerlaubt")
        second.send()
        testScheduler.advanceUntilIdle()

        assertTrue(second.state.value.turnActive)
        assertEquals(runId, second.state.value.boundRunId)
        assertEquals("a same-session send must not open a second stream", 1, chat.calls.get())
        assertFalse(second.state.value.startConflict)
    }

    @Test
    fun theConflictNoticeClearsWhenTheGlobalRunIsTerminalAndNeverAutoSends() =
        withRecreatedModel(sessionKeyOfSecond = "other-chat") { second, chat, _, _ ->
            second.onDraftChange("warten")
            second.send()
            testScheduler.advanceUntilIdle()
            assertTrue(second.state.value.startConflict)
            assertEquals(1, chat.calls.get())

            // Run A reaches a real terminal state: the slot is free again.
            chat.enqueue(ChatTurnEvent.TurnCompleted())
            testScheduler.advanceUntilIdle()

            assertFalse(
                "the notice must clear once the global run is terminal",
                second.state.value.startConflict
            )
            assertEquals(
                "the waiting draft stays and is never auto-sent",
                "warten",
                second.state.value.draft
            )
            assertEquals("no additional POST was triggered", 1, chat.calls.get())
            assertFalse(second.state.value.turnActive)
        }

    @Test
    fun aStartConflictDuringAnAttachmentSendRollsBackWithoutLosingTheAttachment() = runTest {
        val chat = FakeTurnSource()
        val starter = FakeRunServiceStarter()
        val controller = RunSessionController(
            chatSource = chat,
            serviceStarter = starter,
            notificationCoordinator = NoOpNotificationCoordinator,
            dispatcher = UnconfinedTestDispatcher(testScheduler)
        )
        lastController = controller
        val gate = CompletableDeferred<Unit>()
        val attachmentSource = FakeAttachmentSource().apply {
            metadata = mapOf(
                "content://b" to ChatAttachmentMetadata("b.png", "image/png", 10L)
            )
            dataUrls = mapOf("content://b" to "data:image/png;base64,AA==")
            dataUrlGate = gate
        }
        val b = build(
            testScheduler,
            chat = chat,
            controller = controller,
            attachmentSource = attachmentSource,
            sessionKey = "other-chat"
        )
        b.loadHistory()
        testScheduler.advanceUntilIdle()
        b.onAttachmentPicked("content://b")
        testScheduler.advanceUntilIdle()
        b.onDraftChange("mit Bild")
        b.send()
        // The data-URL read is parked: B reserved the turn locally, but
        // nothing reached the controller yet.
        testScheduler.advanceUntilIdle()
        assertTrue(b.state.value.turnActive)
        assertEquals(0, chat.calls.get())

        // Meanwhile another screen starts a run and takes the global slot.
        val a = build(testScheduler, chat = chat, controller = controller, sessionKey = sessionId)
        a.loadHistory()
        testScheduler.advanceUntilIdle()
        a.onDraftChange("A")
        a.send()
        chat.enqueue(ChatTurnEvent.RunStarted(runId))
        testScheduler.advanceUntilIdle()
        assertEquals(1, chat.calls.get())

        // Release B's read: its start attempt is now rejected by the
        // authoritative controller check, and the optimistic state rolls back.
        gate.complete(Unit)
        testScheduler.advanceUntilIdle()

        assertFalse(b.state.value.turnActive)
        assertTrue(b.state.value.startConflict)
        assertNotNull("the attachment must survive the rejected race", b.state.value.attachment)
        assertEquals("mit Bild", b.state.value.draft)
        assertTrue("the optimistic user line must be rolled back", b.state.value.localLines.isEmpty())
        assertEquals("no second POST", 1, chat.calls.get())
        assertEquals("no attachment POST was ever opened", 0, chat.attachmentCalls.get())
        assertEquals("only run A started the foreground service", 1, starter.starts.get())
        assertEquals("the conflict must not stop the foreground service", 0, starter.stops.get())
        assertFalse("run A must not be canceled by the client", chat.canceled.get())

        b.close()
        a.close()
        controller.close()
        lastController = null
    }

    @Test
    fun refreshHistoryKeepsProcessingTheStillActiveRun() = withModel { model, chat, _, _, _ ->
        model.onDraftChange("hi")
        model.send()
        chat.enqueue(ChatTurnEvent.RunStarted(runId))
        testScheduler.advanceUntilIdle()
        assertEquals(runId, model.state.value.boundRunId)

        // Refreshing after a failure reloads history but the run is still
        // owned and active, so its later stream events must keep applying.
        model.refreshHistory()
        testScheduler.advanceUntilIdle()
        chat.enqueue(ChatTurnEvent.AssistantDelta("laufend"))
        testScheduler.advanceUntilIdle()

        assertTrue(model.state.value.turnActive)
        assertEquals(1, model.state.value.localLines.count { it.role == MessageRole.ASSISTANT })
        assertEquals("laufend", model.state.value.localLines.last().content)
    }

    /** Synthetic streaming fake: [streamTurn] is latched, events enqueued, closed by the test. */
    private class FakeTurnSource : StreamingChatSource {
        val calls = AtomicInteger()
        val opened = CountDownLatch(1)
        val completed = AtomicBoolean(false)
        val canceled = AtomicBoolean(false)
        val attachmentCalls = AtomicInteger()
        @Volatile
        var lastMessage: String? = null
        @Volatile
        var lastImageDataUrls: List<String> = emptyList()

        @Volatile
        private var active: Channel<ChatTurnEvent>? = null

        override fun streamTurn(profileId: String, sessionId: String, message: String): Flow<ChatTurnEvent> {
            calls.incrementAndGet()
            lastMessage = message
            val channel = Channel<ChatTurnEvent>(Channel.UNLIMITED)
            active = channel
            opened.countDown()
            return flow {
                for (event in channel) emit(event)
            }.onCompletion { cause ->
                if (cause is CancellationException) canceled.set(true) else completed.set(true)
            }
        }

        override fun streamTurnWithAttachments(
            profileId: String,
            sessionId: String,
            message: String,
            imageDataUrls: List<String>
        ): Flow<ChatTurnEvent> {
            attachmentCalls.incrementAndGet()
            lastImageDataUrls = imageDataUrls
            return streamTurn(profileId, sessionId, message)
        }

        fun enqueue(vararg events: ChatTurnEvent) {
            active?.let { channel -> events.forEach { channel.trySend(it) } }
        }

        fun complete() {
            active?.close()
        }
    }

    /**
     * Synthetic attachment source: per-URI metadata and inline data
     * URLs (missing entry = `null` read), per-URI throws, a suspending gate
     * to hold `readMetadata` (the view model's busy window), and call
     * capture.
     */
    private class FakeAttachmentSource : ChatAttachmentSource {
        val metadataCalls = AtomicInteger()
        val dataUrlCalls = AtomicInteger()
        @Volatile
        var metadata: Map<String, ChatAttachmentMetadata?> = emptyMap()
        @Volatile
        var dataUrls: Map<String, String?> = emptyMap()
        @Volatile
        var throwingMetadataUris: Set<String> = emptySet()
        @Volatile
        var throwingDataUrlUris: Set<String> = emptySet()
        /** When non-null, `readMetadata` suspends until it completes. */
        @Volatile
        var metadataGate: CompletableDeferred<Unit>? = null
        /** When non-null, `readInlineDataUrlResult` suspends until it completes. */
        @Volatile
        var dataUrlGate: CompletableDeferred<Unit>? = null
        @Volatile
        var lastDataUrlRequest: Triple<String, String, Long>? = null

        override suspend fun readMetadata(contentUri: String): ChatAttachmentMetadata? {
            metadataCalls.incrementAndGet()
            if (contentUri in throwingMetadataUris) throw IllegalStateException("provider gone")
            metadataGate?.await()
            return metadata[contentUri]
        }

        override suspend fun readInlineDataUrl(
            contentUri: String,
            mimeType: String,
            maxBytes: Long
        ): String? {
            dataUrlCalls.incrementAndGet()
            lastDataUrlRequest = Triple(contentUri, mimeType, maxBytes)
            if (contentUri in throwingDataUrlUris) throw IllegalStateException("provider gone")
            return dataUrls[contentUri]
        }

        /** Per-URI closed outcomes of `readInlineDataUrlResult`. */
        @Volatile
        var dataUrlResults: Map<String, ChatAttachmentReadResult> = emptyMap()

        override suspend fun readInlineDataUrlResult(
            contentUri: String,
            mimeType: String,
            maxBytes: Long
        ): ChatAttachmentReadResult {
            dataUrlCalls.incrementAndGet()
            lastDataUrlRequest = Triple(contentUri, mimeType, maxBytes)
            dataUrlGate?.await()
            if (contentUri in throwingDataUrlUris) throw IllegalStateException("provider gone")
            dataUrlResults[contentUri]?.let { return it }
            return dataUrls[contentUri]
                ?.let { ChatAttachmentReadResult.DataUrl(it) }
                ?: ChatAttachmentReadResult.Unreadable
        }
    }

    /** Synthetic foreground-service seam: records start/stop calls. */
    private class FakeRunServiceStarter : RunForegroundServiceStarter {
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

    /** Synthetic stop fake: returns a pre-set result; records calls and parameters. */
    private class FakeStopRequester : RunStopRequester {
        val calls = AtomicInteger()
        val started = CountDownLatch(1)

        @Volatile
        var lastRunId: String? = null

        @Volatile
        var result: RunStopResult = RunStopResult.Accepted("run_" + "a".repeat(32))

        override suspend fun stopRun(profileId: String, runId: String): RunStopResult {
            calls.incrementAndGet()
            lastRunId = runId
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

    /** Synthetic run-status fake: returns a pre-set result; records calls and parameters. */
    private class FakeRunStatusRequester : RunStatusRequester {
        val calls = AtomicInteger()

        @Volatile
        var lastRunId: String? = null

        @Volatile
        var result: RunStatusResult = RunStatusResult.Known(RunServerStatus.RUNNING)

        override suspend fun getRunStatus(profileId: String, runId: String): RunStatusResult {
            calls.incrementAndGet()
            lastRunId = runId
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
