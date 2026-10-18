package io.github.excludebarrier.orsikrelay

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import io.github.excludebarrier.orsikrelay.data.chat.ChatDraftRepository
import io.github.excludebarrier.orsikrelay.domain.chat.ChatStreamError
import io.github.excludebarrier.orsikrelay.domain.chat.ChatTurnEvent
import io.github.excludebarrier.orsikrelay.domain.chat.StreamingChatSource
import io.github.excludebarrier.orsikrelay.domain.message.HermesChatMessage
import io.github.excludebarrier.orsikrelay.domain.message.MessageHistoryResult
import io.github.excludebarrier.orsikrelay.domain.message.MessageHistorySource
import io.github.excludebarrier.orsikrelay.domain.message.MessagePage
import io.github.excludebarrier.orsikrelay.domain.message.MessageRole
import io.github.excludebarrier.orsikrelay.domain.run.ApprovalRequest
import io.github.excludebarrier.orsikrelay.domain.run.NoOpRunStatusRequester
import io.github.excludebarrier.orsikrelay.domain.run.RunServerStatus
import io.github.excludebarrier.orsikrelay.domain.run.RunStatusRequester
import io.github.excludebarrier.orsikrelay.domain.run.RunStatusResult
import io.github.excludebarrier.orsikrelay.domain.run.RunStopError
import io.github.excludebarrier.orsikrelay.domain.run.RunStopRequester
import io.github.excludebarrier.orsikrelay.domain.run.RunStopResult
import io.github.excludebarrier.orsikrelay.domain.run.RunSteerError
import io.github.excludebarrier.orsikrelay.domain.run.RunSteerRequester
import io.github.excludebarrier.orsikrelay.domain.run.RunSteerResult
import io.github.excludebarrier.orsikrelay.domain.run.RunForegroundServiceStarter
import io.github.excludebarrier.orsikrelay.domain.run.RunSessionController
import io.github.excludebarrier.orsikrelay.draft.NoOpChatDraftRepository
import io.github.excludebarrier.orsikrelay.ui.chat.ChatScreen
import io.github.excludebarrier.orsikrelay.ui.chat.NoOpNotificationCoordinator
import io.github.excludebarrier.orsikrelay.ui.chat.TAG_RUN_STOP
import io.github.excludebarrier.orsikrelay.ui.theme.HermesTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Synthetic Compose tests for the live turn slice of the chat screen.
 * The streaming seam is a fake: no network, no real hosts, no credentials.
 */
class ChatScreenTurnUiTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val noOpRunServiceStarter = object : RunForegroundServiceStarter {
        override fun start(profileId: String, sessionId: String) {}
        override fun stop() {}
    }

    private fun controllerFor(source: StreamingChatSource) = RunSessionController(
        chatSource = source,
        serviceStarter = noOpRunServiceStarter,
        notificationCoordinator = NoOpNotificationCoordinator
    )

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun composerAppearsAfterHistoryLoadAndBlankInputIsDisabled() {
        val source = FakeTurnSource()
        setScreen(history(emptyList()), source)

        waitForText(context.getString(R.string.chat_empty))
        composeRule.onNodeWithText(context.getString(R.string.chat_input_label)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.chat_send)).assertIsDisplayed()
        // Empty draft: the send button is disabled and a tap sends nothing.
        composeRule.onNodeWithText(context.getString(R.string.chat_send)).assertIsNotEnabled()
        composeRule.onNodeWithText(context.getString(R.string.chat_send)).performClick()
        assertEquals(0, source.calls.get())

        // Whitespace draft: also disabled.
        composeRule.onNodeWithText(context.getString(R.string.chat_input_label)).performTextInput(" \n \t ")
        composeRule.onNodeWithText(context.getString(R.string.chat_send)).assertIsNotEnabled()
        assertEquals(" \n \t ", draftText())
        assertEquals(0, source.calls.get())
    }

    @Test
    fun doubleTapStartsExactlyOneStreamAndKeepsDraftUntilRunStarted() {
        val source = FakeTurnSource()
        setScreen(history(emptyList()), source)

        waitForText(context.getString(R.string.chat_empty))
        composeRule
            .onNodeWithText(context.getString(R.string.chat_input_label))
            .performTextInput("  Doppelte Eingabe  ")
        ImeClickSettle.settleAfterTyping(composeRule)
        composeRule.onNodeWithText(context.getString(R.string.chat_send)).performClick()
        assertTrue("first stream was not started", source.opened.await(3, TimeUnit.SECONDS))
        // Second, quick tap while the turn is running.
        composeRule.onNodeWithText(context.getString(R.string.chat_send)).performClick()
        assertEquals("exactly one stream must run", 1, source.calls.get())
        // The local user line appears immediately after an accepted submit.
        waitForText("Doppelte Eingabe")
        // Before RunStarted the draft is kept in the field.
        assertEquals("  Doppelte Eingabe  ", draftText())

        source.enqueue(ChatTurnEvent.RunStarted("run_synthetic"))
        // Only RunStarted clears the draft.
        composeRule.waitUntil(timeoutMillis = 5_000) {
            draftText().isNullOrBlank()
        }
        composeRule.waitForIdle()
        assertEquals(1, source.calls.get())

        source.enqueue(ChatTurnEvent.TurnCompleted())
        source.complete()
        waitForFlowCompletion(source)
        // TurnCompleted frees the composer: the field is editable again.
        // With an empty draft the send button is visible but disabled.
        composeRule.onNodeWithText(context.getString(R.string.chat_input_label)).assertIsEnabled()
        composeRule.onNodeWithText(context.getString(R.string.chat_send)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.chat_send)).assertIsNotEnabled()
    }

    @Test
    fun deltasGrowOneLineAndFinalTextReplacesIt() {
        val source = FakeTurnSource()
        setScreen(history(emptyList()), source)

        waitForText(context.getString(R.string.chat_empty))
        composeRule.onNodeWithText(context.getString(R.string.chat_input_label)).performTextInput("Hallo")
        ImeClickSettle.settleAfterTyping(composeRule)
        composeRule.onNodeWithText(context.getString(R.string.chat_send)).performClick()

        // Both deltas visibly grow in the same line. The stream is only
        // continued after the intermediate state has been observed, because
        // the final event replaces the composed line.
        source.enqueue(
            ChatTurnEvent.RunStarted("run_synthetic"),
            ChatTurnEvent.AssistantStarted,
            ChatTurnEvent.AssistantDelta("Delta-1"),
            ChatTurnEvent.AssistantDelta("Delta-2")
        )
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Delta-1Delta-2")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Delta-1Delta-2").assertIsDisplayed()

        source.enqueue(
            ChatTurnEvent.AssistantCompleted("Finaler Text"),
            ChatTurnEvent.TurnCompleted()
        )
        source.complete()
        // The final text replaces the composed line.
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Finaler Text")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()
        assertTrue(
            "composed deltas must be replaced by the final text",
            composeRule.onAllNodesWithText("Delta-1Delta-2")
                .fetchSemanticsNodes().isEmpty()
        )
        // TurnCompleted frees the composer: the field is editable again.
        // With an empty draft the send button is visible but disabled.
        composeRule.onNodeWithText(context.getString(R.string.chat_input_label)).assertIsEnabled()
        composeRule.onNodeWithText(context.getString(R.string.chat_send)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.chat_send)).assertIsNotEnabled()
        waitForFlowCompletion(source)
    }

    @Test
    fun toolActivityIsGenericWithoutNameArgsOrReasoning() {
        val source = FakeTurnSource()
        setScreen(history(emptyList()), source)

        waitForText(context.getString(R.string.chat_empty))
        composeRule
            .onNodeWithText(context.getString(R.string.chat_input_label))
            .performTextInput("Werkzeugtest")
        ImeClickSettle.settleAfterTyping(composeRule)
        composeRule.onNodeWithText(context.getString(R.string.chat_send)).performClick()

        source.enqueue(
            ChatTurnEvent.RunStarted("run_synthetic"),
            ChatTurnEvent.AssistantStarted,
            ChatTurnEvent.ToolActivity()
        )
        // Only the generic status is visible. The stream is only continued
        // after the observation, because the final line replaces the
        // composed content.
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(context.getString(R.string.chat_tool_activity))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(context.getString(R.string.chat_tool_activity)).assertIsDisplayed()
        // Synthetic tool name/args/preview/reasoning appear nowhere.
        listOf(
            "synthetic_tool_name",
            "synthetic-args",
            "synthetic-reasoning",
            "synthetic-preview"
        ).forEach { forbidden ->
            assertTrue(
                "$forbidden must not appear in the UI",
                composeRule.onAllNodesWithText(forbidden)
                    .fetchSemanticsNodes().isEmpty()
            )
        }

        source.enqueue(
            ChatTurnEvent.AssistantCompleted("Ende"),
            ChatTurnEvent.TurnCompleted()
        )
        source.complete()
        waitForFlowCompletion(source)
        // With an empty draft the send button is visible but disabled.
        composeRule.onNodeWithText(context.getString(R.string.chat_send)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.chat_send)).assertIsNotEnabled()
    }

    @Test
    fun uncertainFailureBeforeStartKeepsDraftBlocksTheComposerAndOffersNoRecovery() {
        val source = FakeTurnSource()
        setScreen(history(emptyList()), source)

        waitForText(context.getString(R.string.chat_empty))
        composeRule
            .onNodeWithText(context.getString(R.string.chat_input_label))
            .performTextInput("Bleibt im Entwurf")
        ImeClickSettle.settleAfterTyping(composeRule)
        composeRule.onNodeWithText(context.getString(R.string.chat_send)).performClick()

        source.enqueue(ChatTurnEvent.TurnFailed(ChatStreamError.Network))
        source.complete()
        waitForFlowCompletion(source)

        // Without a confirmed start the POST may still have been accepted,
        // so the outcome is UNKNOWN. The draft survives, the composer is locked
        // (no second POST) and the honest uncertain notice is shown - never a
        // claim that the server run ended. No history refresh and, without a
        // confirmed run ID, no manual status recovery are offered.
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag(UNCERTAIN_TAG).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()
        assertEquals("Bleibt im Entwurf", draftText())
        composeRule.onNodeWithText(context.getString(R.string.chat_send)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.chat_send)).assertIsNotEnabled()
        composeRule.onNodeWithText(context.getString(R.string.chat_input_label)).assertIsNotEnabled()
        assertTrue(
            "no history refresh while the run outcome is unknown",
            composeRule.onAllNodesWithText(context.getString(R.string.chat_refresh_history))
                .fetchSemanticsNodes().isEmpty()
        )
        assertTrue(
            "no confirmed run ID: no status recovery",
            composeRule.onAllNodesWithTag(RECONCILE_TAG).fetchSemanticsNodes().isEmpty()
        )
        assertEquals(1, source.calls.get())
    }

    @Test
    fun confirmedTransportFailureOffersManualStatusRecoveryWithoutResending() {
        val historyCalls = AtomicInteger()
        val status = FakeRunStatusRequester()
        val source = FakeTurnSource()
        setScreen(
            MessageHistorySource { _, _ ->
                historyCalls.incrementAndGet()
                MessageHistoryResult.Success(MessagePage(emptyList()))
            },
            source,
            runStatusRequester = status
        )

        waitForText(context.getString(R.string.chat_empty))
        composeRule
            .onNodeWithText(context.getString(R.string.chat_input_label))
            .performTextInput("Nachrichten")
        ImeClickSettle.settleAfterTyping(composeRule)
        composeRule.onNodeWithText(context.getString(R.string.chat_send)).performClick()

        source.enqueue(
            ChatTurnEvent.RunStarted("run_synthetic"),
            ChatTurnEvent.TurnFailed(ChatStreamError.Network)
        )
        source.complete()
        waitForFlowCompletion(source)

        // The run was confirmed, so a transport failure leaves the server
        // outcome UNKNOWN. The composer stays locked, no bare history refresh
        // is offered and the chat POST is never resent. The confirmed run ID
        // enables the explicit manual status recovery.
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag(UNCERTAIN_TAG).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()
        assertEquals(1, historyCalls.get())
        assertEquals("no automatic resending", 1, source.calls.get())
        assertTrue(
            "no bare history refresh while the run outcome is unknown",
            composeRule.onAllNodesWithText(context.getString(R.string.chat_refresh_history))
                .fetchSemanticsNodes().isEmpty()
        )
        composeRule.onNodeWithTag(RECONCILE_TAG).assertIsDisplayed()
        // The confirmed run ID enables recovery but is never rendered (no ID,
        // URL or credential may reach the UI).
        assertTrue(
            "the run id must never be rendered",
            composeRule.onAllNodesWithText(RUN_ID).fetchSemanticsNodes().isEmpty()
        )

        // Manual recovery reads the server status. A TERMINAL status is the
        // authoritative answer: it releases the run, reconciles the visible
        // turn from the idempotent history read and never resends the POST.
        status.result = RunStatusResult.Known(RunServerStatus.COMPLETED)
        composeRule.onNodeWithTag(RECONCILE_TAG).performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { historyCalls.get() == 2 }
        composeRule.waitForIdle()
        assertEquals(1, status.calls.get())
        assertEquals("run_synthetic", status.runId.get())
        assertEquals(2, historyCalls.get())
        assertEquals("the authoritative terminal never resends the chat POST", 1, source.calls.get())
        composeRule.onNodeWithText(context.getString(R.string.chat_input_label)).assertIsEnabled()
        assertTrue(
            "the uncertain notice must be gone after the authoritative terminal",
            composeRule.onAllNodesWithTag(UNCERTAIN_TAG).fetchSemanticsNodes().isEmpty()
        )
    }

    @Test
    fun authoritativeFailureBeforeStartKeepsTheDraftAndExplainsThatItWasNotSent() {
        val source = FakeTurnSource()
        setScreen(history(emptyList()), source)

        waitForText(context.getString(R.string.chat_empty))
        composeRule
            .onNodeWithText(context.getString(R.string.chat_input_label))
            .performTextInput("Nicht gesendet")
        ImeClickSettle.settleAfterTyping(composeRule)
        composeRule.onNodeWithText(context.getString(R.string.chat_send)).performClick()

        // A provably rejected initial POST (non-2xx HTTP) has no confirmed
        // run: the turn settles and the failure card is shown. The draft was
        // never cleared (only `RunStarted` clears it), so the card says so.
        source.enqueue(ChatTurnEvent.TurnFailed(ChatStreamError.Http))
        source.complete()
        waitForFlowCompletion(source)

        waitForText(context.getString(R.string.error_connection_http))
        composeRule.onNodeWithText(context.getString(R.string.chat_turn_failure_draft_kept))
            .assertIsDisplayed()
        assertTrue(
            "no confirmed run: no history refresh action",
            composeRule
                .onAllNodesWithText(context.getString(R.string.chat_refresh_history))
                .fetchSemanticsNodes().isEmpty()
        )
        assertEquals("Nicht gesendet", draftText())
        composeRule.onNodeWithText(context.getString(R.string.chat_input_label)).assertIsEnabled()
        composeRule.onNodeWithText(context.getString(R.string.chat_send)).assertIsEnabled()
    }

    @Test
    fun threeRecoverableSteersCarryTheirCounterAndStaySeparate() {
        val steerer = FakeRunSteerRequester()
        val source = FakeTurnSource()
        steerer.release(RunSteerResult.NotAccepting(RUN_ID))
        setScreen(history(emptyList()), source, runSteerRequester = steerer)

        waitForText(context.getString(R.string.chat_empty))
        composeRule.onNodeWithText(context.getString(R.string.chat_input_label)).performTextInput("Drei-Items")
        ImeClickSettle.settleAfterTyping(composeRule)
        composeRule.onNodeWithText(context.getString(R.string.chat_send)).performClick()
        source.enqueue(ChatTurnEvent.RunStarted(RUN_ID))

        composeRule.onNodeWithTag("chatSteerInput").performTextInput("Steuer-1")
        ImeClickSettle.settleAfterTyping(composeRule)
        composeRule.onNodeWithTag("chatSteerSend").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("chatSteerSendAsNewMessage:0")
                .fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithTag("chatSteerInput").performTextInput("Steuer-2")
        ImeClickSettle.settleAfterTyping(composeRule)
        composeRule.onNodeWithTag("chatSteerSend").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("chatSteerSendAsNewMessage:1")
                .fetchSemanticsNodes().isNotEmpty()
        }

        source.enqueue(ChatTurnEvent.TurnCompleted(pendingSteer = "Steuer-3"))
        source.complete()
        waitForFlowCompletion(source)
        composeRule.waitUntil(timeoutMillis = 5_000) { recoveryItemCount() == 3 }

        // Every card of a multi-item recovery carries its 1-based counter.
        composeRule
            .onNodeWithText(context.getString(R.string.chat_steer_recovery_title_counter, 1, 3))
            .assertExists()
        composeRule
            .onNodeWithText(context.getString(R.string.chat_steer_recovery_title_counter, 2, 3))
            .assertExists()
        composeRule
            .onNodeWithText(context.getString(R.string.chat_steer_recovery_title_counter, 3, 3))
            .assertExists()
        assertTrue(recoveryItemTextPresent("Steuer-1"))
        assertTrue(recoveryItemTextPresent("Steuer-2"))
        assertTrue(recoveryItemTextPresent("Steuer-3"))
        assertEquals("nie automatisch gesendet", 1, source.calls.get())
    }

    @Test
    fun submitScrollsLongHistoryToTheNewLiveMessage() {
        val source = FakeTurnSource()
        val longHistory = (1L..40L).map { id ->
            HermesChatMessage(
                id = id,
                role = MessageRole.ASSISTANT,
                content = "Historie $id",
                timestampSeconds = null,
                toolName = null
            )
        }
        setScreen(history(longHistory), source)

        composeRule.onNodeWithText(context.getString(R.string.chat_input_label)).performTextInput("Neue Live-Nachricht")
        ImeClickSettle.settleAfterTyping(composeRule)
        composeRule.onNodeWithText(context.getString(R.string.chat_send)).performClick()
        assertTrue(source.opened.await(3, TimeUnit.SECONDS))

        waitForText("Neue Live-Nachricht")
        // Scroll the row into view first: with the soft keyboard open the
        // timeline viewport is shorter and the row can be composed but not
        // displayed, which is a geometry artefact, not a scroll defect.
        composeRule
            .onNodeWithTag("chatTimelineRow:local:1")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun backDuringRunningTurnKeepsTheStreamAliveAndInvokesOnBack() {
        val source = FakeTurnSource()
        val backInvoked = CountDownLatch(1)
        setScreen(
            history(emptyList()),
            source,
            onBack = { backInvoked.countDown() }
        )

        waitForText(context.getString(R.string.chat_empty))
        composeRule
            .onNodeWithText(context.getString(R.string.chat_input_label))
            .performTextInput("Läuft noch")
        ImeClickSettle.settleAfterTyping(composeRule)
        composeRule.onNodeWithText(context.getString(R.string.chat_send)).performClick()
        assertTrue(source.opened.await(3, TimeUnit.SECONDS))
        assertEquals(1, source.calls.get())

        composeRule.onNodeWithText(context.getString(R.string.chat_back)).performClick()
        assertTrue("onBack was not invoked", backInvoked.await(3, TimeUnit.SECONDS))
        // Background continuity: the run belongs to the app-wide
        // RunSessionController, not to the screen — back does NOT cancel the
        // stream; the foreground service keeps the process unfrozen while the
        // run continues in the background.
        assertFalse(
            "the running stream must stay alive after back",
            source.canceled.await(100, TimeUnit.MILLISECONDS)
        )
        assertEquals(1, source.calls.get())
    }

    @Test
    fun normalFlowEndWithoutTerminalEventBecomesUncertainAndKeepsComposerBlocked() {
        val source = FakeTurnSource()
        setScreen(history(emptyList()), source)

        waitForText(context.getString(R.string.chat_empty))
        composeRule
            .onNodeWithText(context.getString(R.string.chat_input_label))
            .performTextInput("Plötzliches Ende")
        ImeClickSettle.settleAfterTyping(composeRule)
        composeRule.onNodeWithText(context.getString(R.string.chat_send)).performClick()

        source.enqueue(
            ChatTurnEvent.RunStarted("run_synthetic"),
            ChatTurnEvent.AssistantStarted
        )
        // The stream ends normally, without `TurnCompleted` and without
        // `TurnFailed`.
        source.complete()
        waitForFlowCompletion(source)

        // A confirmed run whose stream vanished without a terminal event
        // has an UNKNOWN outcome. The composer stays locked, the run is never
        // reported as stopped or finished, no history refresh is offered and
        // the confirmed run ID enables the manual "check status" recovery.
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag(UNCERTAIN_TAG).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(context.getString(R.string.chat_send)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.chat_send)).assertIsNotEnabled()
        assertTrue(
            "the confirmed run ID must offer the manual status recovery",
            composeRule.onAllNodesWithTag(RECONCILE_TAG).fetchSemanticsNodes().isNotEmpty()
        )
        assertTrue(
            "no history refresh while the run outcome is unknown",
            composeRule.onAllNodesWithText(context.getString(R.string.chat_refresh_history))
                .fetchSemanticsNodes().isEmpty()
        )
        assertEquals("no automatic retry", 1, source.calls.get())
    }

    @Test
    fun synchronousSourceThatEndsInsideStartStillReleasesTheComposer() {
        // Regression for the LAZY job registration: a source whose flow
        // completes SYNCHRONOUSLY inside the collector (no Channel, no
        // async boundary) must not orphan `turnActive`. The old
        // eager-launch form assigned the job AFTER the collector had
        // already finished, so the `finally` bookkeeping saw a foreign
        // (null) job and the composer stayed locked. The existing
        // async `FakeTurnSource` never exercises this window.
        val synchronousSource = object : StreamingChatSource {
            override fun streamTurn(
                profileId: String,
                sessionId: String,
                message: String
            ): Flow<ChatTurnEvent> = flow {
                emit(ChatTurnEvent.RunStarted("run_synthetic"))
                emit(ChatTurnEvent.AssistantStarted)
                emit(ChatTurnEvent.AssistantCompleted("Sofort fertig"))
                emit(ChatTurnEvent.TurnCompleted())
            }
        }
        setScreen(history(emptyList()), synchronousSource)

        waitForText(context.getString(R.string.chat_empty))
        composeRule.onNodeWithText(context.getString(R.string.chat_input_label)).performTextInput("Sofortig")
        ImeClickSettle.settleAfterTyping(composeRule)
        composeRule.onNodeWithText(context.getString(R.string.chat_send)).performClick()

        // The final text is rendered and the turn is released: the
        // field is editable again and the (empty) draft keeps the send button
        // disabled. With the bug present `turnActive` would never clear
        // and the field would stay disabled forever.
        waitForText("Sofort fertig")
        composeRule.onNodeWithText(context.getString(R.string.chat_input_label)).assertIsEnabled()
        composeRule.onNodeWithText(context.getString(R.string.chat_send)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.chat_send)).assertIsNotEnabled()
    }

    @Test
    fun completedEventReleasesComposerWithoutSourceCompletion() {
        val source = FakeTurnSource()
        setScreen(history(emptyList()), source)

        waitForText(context.getString(R.string.chat_empty))
        composeRule.onNodeWithText(context.getString(R.string.chat_input_label)).performTextInput("Abschluss")
        ImeClickSettle.settleAfterTyping(composeRule)
        composeRule.onNodeWithText(context.getString(R.string.chat_send)).performClick()
        assertTrue(source.opened.await(3, TimeUnit.SECONDS))

        source.enqueue(ChatTurnEvent.RunStarted("run_synthetic"), ChatTurnEvent.TurnCompleted())

        composeRule.waitUntil(timeoutMillis = 5_000) {
            try {
                composeRule.onNodeWithText(context.getString(R.string.chat_input_label)).assertIsEnabled()
                true
            } catch (_: AssertionError) {
                false
            }
        }
        assertEquals("stream must survive the terminal event", 1L, source.canceled.count)
    }

    @Test
    fun confirmedTransportFailureWithoutSourceCompletionKeepsComposerBlocked() {
        // A confirmed run plus a LOCAL transport failure does
        // NOT prove the server run ended, so it must never release the
        // composer - even when the transport has not been closed. The
        // counterpart of the authoritative release below.
        val source = FakeTurnSource()
        setScreen(history(emptyList()), source)

        waitForText(context.getString(R.string.chat_empty))
        composeRule.onNodeWithText(context.getString(R.string.chat_input_label)).performTextInput("Fehler")
        ImeClickSettle.settleAfterTyping(composeRule)
        composeRule.onNodeWithText(context.getString(R.string.chat_send)).performClick()
        assertTrue(source.opened.await(3, TimeUnit.SECONDS))

        source.enqueue(
            ChatTurnEvent.RunStarted("run_synthetic"),
            ChatTurnEvent.TurnFailed(ChatStreamError.Network)
        )

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag(UNCERTAIN_TAG).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(context.getString(R.string.chat_input_label)).assertIsNotEnabled()
        composeRule.onNodeWithText(context.getString(R.string.chat_send)).assertIsNotEnabled()
        assertTrue(
            "a transport failure must never be shown as a finished run",
            composeRule.onAllNodesWithText(context.getString(R.string.chat_refresh_history))
                .fetchSemanticsNodes().isEmpty()
        )
        assertEquals("no second POST", 1, source.calls.get())
    }

    @Test
    fun authoritativeServerErrorReleasesComposerWithoutSourceCompletion() {
        // ONLY a server-authoritative terminal releases the run
        // slot. `ChatStreamError.ServerError` is the server's own terminal
        // `error` frame (the run status is terminal server-side), so the
        // composer is freed even though the transport was not closed by the
        // source and no `TurnCompleted` arrived.
        val source = FakeTurnSource()
        setScreen(history(emptyList()), source)

        waitForText(context.getString(R.string.chat_empty))
        composeRule.onNodeWithText(context.getString(R.string.chat_input_label)).performTextInput("Serverfehler")
        ImeClickSettle.settleAfterTyping(composeRule)
        composeRule.onNodeWithText(context.getString(R.string.chat_send)).performClick()
        assertTrue(source.opened.await(3, TimeUnit.SECONDS))

        source.enqueue(
            ChatTurnEvent.RunStarted("run_synthetic"),
            ChatTurnEvent.TurnFailed(ChatStreamError.ServerError)
        )

        composeRule.waitUntil(timeoutMillis = 5_000) {
            try {
                composeRule.onNodeWithText(context.getString(R.string.chat_input_label)).assertIsEnabled()
                true
            } catch (_: AssertionError) {
                false
            }
        }
        // The release came from the server-authoritative event, not from the
        // stream ending: the synthetic source was never completed.
        assertEquals("stream must survive the terminal event", 1L, source.canceled.count)
        assertFalse(source.completed.get())
        assertTrue(
            "an authoritative terminal must not leave the uncertain notice",
            composeRule.onAllNodesWithTag(UNCERTAIN_TAG).fetchSemanticsNodes().isEmpty()
        )
    }

    @Test
    fun sessionSwitchKeepsTheRunRunningAndResetsEphemeralState() {
        val firstSource = FakeTurnSource()
        val secondSource = FakeTurnSource()
        val key = androidx.compose.runtime.mutableStateOf("first-session")
        composeRule.setContent {
            HermesTheme {
                ChatScreen(
                    historySource = history(emptyList()),
                    runController = controllerFor(if (key.value == "first-session") firstSource else secondSource),
                    runStopRequester = FakeRunStopRequester(),
                    runSteerRequester = FakeRunSteerRequester(),
                    chatDraftRepository = NoOpChatDraftRepository(),
                    profileId = "fixture-profile-id",
                    sessionId = key.value,
                    onBack = {}
                )
            }
        }

        waitForText(context.getString(R.string.chat_empty))
        composeRule
            .onNodeWithText(context.getString(R.string.chat_input_label))
            .performTextInput("Läuft in erster Session")
        ImeClickSettle.settleAfterTyping(composeRule)
        composeRule.onNodeWithText(context.getString(R.string.chat_send)).performClick()
        assertTrue(firstSource.opened.await(3, TimeUnit.SECONDS))
        waitForText(context.getString(R.string.chat_role_user))

        // Background continuity: the running run belongs to the app-wide
        // RunSessionController, not to the screen — a session switch does NOT
        // cancel it. Here the first (orphaned) controller stands in for the
        // app-wide owner: its stream stays open.
        key.value = "second-session"
        composeRule.waitForIdle()

        assertFalse(
            "the running stream must survive a session switch",
            firstSource.canceled.await(100, TimeUnit.MILLISECONDS)
        )
        assertEquals(1, firstSource.calls.get())
        assertEquals(0, secondSource.calls.get())
        assertTrue(
            "ephemeral turn state must be reset",
            composeRule.onAllNodesWithText("Läuft in erster Session")
                .fetchSemanticsNodes().isEmpty()
        )
        // The history of the new session is loaded, the composer is free.
        waitForText(context.getString(R.string.chat_empty))
        composeRule.onNodeWithText(context.getString(R.string.chat_input_label)).assertIsEnabled()
    }

    @Test
    fun sendInAnotherSessionIsRejectedWhileTheGlobalRunIsActive() {
        // ONE app-wide controller shared by both sessions (the production
        // ownership boundary), unlike the per-key controller above.
        val source = FakeTurnSource()
        val controller = controllerFor(source)
        val key = androidx.compose.runtime.mutableStateOf("session-a")
        composeRule.setContent {
            HermesTheme {
                ChatScreen(
                    historySource = history(emptyList()),
                    runController = controller,
                    runStopRequester = FakeRunStopRequester(),
                    runSteerRequester = FakeRunSteerRequester(),
                    chatDraftRepository = NoOpChatDraftRepository(),
                    profileId = "fixture-profile-id",
                    sessionId = key.value,
                    onBack = {}
                )
            }
        }

        // Session A starts a run that stays open.
        waitForText(context.getString(R.string.chat_empty))
        composeRule.onNodeWithText(context.getString(R.string.chat_input_label)).performTextInput("Lauf A")
        ImeClickSettle.settleAfterTyping(composeRule)
        composeRule.onNodeWithText(context.getString(R.string.chat_send)).performClick()
        assertTrue("run A was not started", source.opened.await(3, TimeUnit.SECONDS))
        source.enqueue(ChatTurnEvent.RunStarted(RUN_ID))
        waitForText("Lauf A")
        assertEquals(1, source.calls.get())

        // Switch to session B while run A still runs.
        key.value = "session-b"
        composeRule.waitForIdle()
        waitForText(context.getString(R.string.chat_empty))

        // A send attempt in B is rejected: generic notice, no second POST,
        // B's draft is kept and run A is not interrupted.
        composeRule.onNodeWithText(context.getString(R.string.chat_input_label)).performTextInput("Lauf B")
        ImeClickSettle.settleAfterTyping(composeRule)
        composeRule.onNodeWithText(context.getString(R.string.chat_send)).performClick()
        waitForText(CONFLICT_TEXT)
        assertEquals("exactly one POST in total", 1, source.calls.get())
        assertEquals("session B keeps its draft", "Lauf B", draftText())
        assertFalse(
            "run A must not be canceled by the client",
            source.canceled.await(100, TimeUnit.MILLISECONDS)
        )

        // Run A reaches a real terminal state: the notice clears without
        // auto-sending the waiting draft.
        source.enqueue(ChatTurnEvent.TurnCompleted())
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(CONFLICT_TEXT).fetchSemanticsNodes().isEmpty()
        }
        composeRule.waitForIdle()
        assertEquals("the waiting draft is not auto-sent", 1, source.calls.get())
        assertEquals("Lauf B", draftText())

        // A deliberate new send in B now starts exactly one new run.
        composeRule.onNodeWithText(context.getString(R.string.chat_send)).performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { source.calls.get() == 2 }
        assertEquals(2, source.calls.get())
    }

    /**
     * Capture-only state: run A active, session B shows the conflict notice
     * with its draft preserved. No assertions; run on a headed emulator for
     * manual inspection (light and dark system themes).
     */
    @CaptureOnly
    @Test
    fun conflictVisualStatePortrait() {
        val source = FakeTurnSource()
        val controller = controllerFor(source)
        val key = androidx.compose.runtime.mutableStateOf("session-a")
        composeRule.setContent {
            HermesTheme {
                ChatScreen(
                    historySource = history(emptyList()),
                    runController = controller,
                    runStopRequester = FakeRunStopRequester(),
                    runSteerRequester = FakeRunSteerRequester(),
                    chatDraftRepository = NoOpChatDraftRepository(),
                    profileId = "fixture-profile-id",
                    sessionId = key.value,
                    onBack = {}
                )
            }
        }
        waitForText(context.getString(R.string.chat_empty))
        composeRule.onNodeWithText(context.getString(R.string.chat_input_label)).performTextInput("Lauf A")
        ImeClickSettle.settleAfterTyping(composeRule)
        composeRule.onNodeWithText(context.getString(R.string.chat_send)).performClick()
        assertTrue(source.opened.await(3, TimeUnit.SECONDS))
        source.enqueue(ChatTurnEvent.RunStarted(RUN_ID))
        waitForText("Lauf A")
        key.value = "session-b"
        composeRule.waitForIdle()
        waitForText(context.getString(R.string.chat_empty))
        composeRule.onNodeWithText(context.getString(R.string.chat_input_label)).performTextInput("Lauf B")
        ImeClickSettle.settleAfterTyping(composeRule)
        composeRule.onNodeWithText(context.getString(R.string.chat_send)).performClick()
        waitForText(CONFLICT_TEXT)
        println("SHOOT: p6_conflict_portrait")
        Thread.sleep(20_000)
    }

    /**
     * Capture-only state in the dark colour scheme (explicit override, like
     * the existing dark IME capture). No assertions.
     */
    @CaptureOnly
    @Test
    fun conflictVisualStatePortraitDark() {
        val source = FakeTurnSource()
        val controller = controllerFor(source)
        val key = androidx.compose.runtime.mutableStateOf("session-a")
        composeRule.setContent {
            HermesTheme(darkTheme = true) {
                ChatScreen(
                    historySource = history(emptyList()),
                    runController = controller,
                    runStopRequester = FakeRunStopRequester(),
                    runSteerRequester = FakeRunSteerRequester(),
                    chatDraftRepository = NoOpChatDraftRepository(),
                    profileId = "fixture-profile-id",
                    sessionId = key.value,
                    onBack = {}
                )
            }
        }
        waitForText(context.getString(R.string.chat_empty))
        composeRule.onNodeWithText(context.getString(R.string.chat_input_label)).performTextInput("Lauf A")
        ImeClickSettle.settleAfterTyping(composeRule)
        composeRule.onNodeWithText(context.getString(R.string.chat_send)).performClick()
        assertTrue(source.opened.await(3, TimeUnit.SECONDS))
        source.enqueue(ChatTurnEvent.RunStarted(RUN_ID))
        waitForText("Lauf A")
        key.value = "session-b"
        composeRule.waitForIdle()
        waitForText(context.getString(R.string.chat_empty))
        composeRule.onNodeWithText(context.getString(R.string.chat_input_label)).performTextInput("Lauf B")
        ImeClickSettle.settleAfterTyping(composeRule)
        composeRule.onNodeWithText(context.getString(R.string.chat_send)).performClick()
        waitForText(CONFLICT_TEXT)
        println("SHOOT: p6_conflict_portrait_dark")
        Thread.sleep(20_000)
    }

    @Test
    fun pendingApprovalDisappearsOnTurnCompleted() {
        val source = FakeTurnSource()
        setScreen(history(emptyList()), source)
        openApproval(source, APPROVAL_REQUEST)

        source.enqueue(ChatTurnEvent.TurnCompleted())
        source.complete()
        waitForFlowCompletion(source)

        assertTrue(composeRule.onAllNodesWithText(APPROVAL_TITLE).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun pendingApprovalDisappearsOnTurnFailure() {
        val source = FakeTurnSource()
        setScreen(history(emptyList()), source)
        openApproval(source, APPROVAL_REQUEST)

        source.enqueue(ChatTurnEvent.TurnFailed(ChatStreamError.Network))
        source.complete()
        waitForFlowCompletion(source)

        assertTrue(composeRule.onAllNodesWithText(APPROVAL_TITLE).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun pendingApprovalDisappearsWhenStreamEndsWithoutTerminalEvent() {
        val source = FakeTurnSource()
        setScreen(history(emptyList()), source)
        openApproval(source, APPROVAL_REQUEST)

        source.complete()
        waitForFlowCompletion(source)

        assertTrue(composeRule.onAllNodesWithText(APPROVAL_TITLE).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun secondConcurrentApprovalNeverOverwritesThePendingRequest() {
        val source = FakeTurnSource()
        setScreen(history(emptyList()), source)
        openApproval(source, APPROVAL_REQUEST)

        source.enqueue(ChatTurnEvent.ApprovalRequested(SECOND_APPROVAL_REQUEST))
        waitForFlowCompletion(source)

        assertTrue(composeRule.onAllNodesWithText(APPROVAL_TITLE).fetchSemanticsNodes().isEmpty())
    }

    private fun openApproval(source: FakeTurnSource, request: ApprovalRequest) {
        waitForText(context.getString(R.string.chat_empty))
        composeRule.onNodeWithText(context.getString(R.string.chat_input_label)).performTextInput("Approval lifecycle")
        ImeClickSettle.settleAfterTyping(composeRule)
        composeRule.onNodeWithText(context.getString(R.string.chat_send)).performClick()
        assertTrue(source.opened.await(3, TimeUnit.SECONDS))
        source.enqueue(
            ChatTurnEvent.RunStarted(request.runId),
            ChatTurnEvent.ApprovalRequested(request)
        )
        waitForText(APPROVAL_TITLE)
    }

    private fun setScreen(
        source: MessageHistorySource,
        chatSource: StreamingChatSource,
        onBack: () -> Unit = {},
        runStopRequester: RunStopRequester = FakeRunStopRequester(),
        runSteerRequester: RunSteerRequester = FakeRunSteerRequester(),
        chatDraftRepository: ChatDraftRepository = NoOpChatDraftRepository(),
        runStatusRequester: RunStatusRequester = NoOpRunStatusRequester
    ) {
        composeRule.setContent {
            HermesTheme {
                ChatScreen(
                    historySource = source,
                    runStopRequester = runStopRequester,
                    runSteerRequester = runSteerRequester,
                    chatDraftRepository = chatDraftRepository,
                    runStatusRequester = runStatusRequester,
                    profileId = "fixture-profile-id",
                    sessionId = "fixture-session-id",
                    runController = controllerFor(chatSource),
                    onBack = onBack
                )
            }
        }
    }

    private fun history(messages: List<HermesChatMessage>) =
        MessageHistorySource { _, _ ->
            MessageHistoryResult.Success(MessagePage(messages))
        }

    private fun draftText(): String? {
        val nodes = composeRule.onAllNodesWithText(context.getString(R.string.chat_input_label)).fetchSemanticsNodes()
        return nodes
            .firstOrNull { it.config.contains(SemanticsProperties.EditableText) }
            ?.config[SemanticsProperties.EditableText]
            ?.text
            ?.toString()
    }

    private fun waitForFlowCompletion(source: FakeTurnSource) {
        // A terminal ChatTurnEvent now stops collection immediately. For
        // an open upstream channel, takeWhile completes downstream by
        // cancelling that upstream collector; a naturally closed source
        // still reports normal completion. Both mean the UI collector has
        // actually stopped, which is what callers of this helper require.
        composeRule.waitUntil(timeoutMillis = 5_000) {
            source.completed.get() || source.canceled.count == 0L
        }
        composeRule.waitForIdle()
    }

    private fun waitForText(text: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()
    }

    /**
     * Synthetic streaming fake: [streamTurn] is latched, events are
     * enqueued and closed by the test. Completion and cancellation are
     * observable. No network, no credentials.
     */
    private class FakeTurnSource : StreamingChatSource {
        val calls = AtomicInteger()
        val profileId = AtomicReference<String>()
        val sessionId = AtomicReference<String>()
        val message = AtomicReference<String>()
        val opened = CountDownLatch(1)
        val canceled = CountDownLatch(1)
        val completed = AtomicBoolean(false)
        @Volatile
        private var activeChannel: Channel<ChatTurnEvent>? = null

        override fun streamTurn(
            profileId: String,
            sessionId: String,
            message: String
        ): Flow<ChatTurnEvent> {
            calls.incrementAndGet()
            this.profileId.set(profileId)
            this.sessionId.set(sessionId)
            this.message.set(message)
            val channel = Channel<ChatTurnEvent>(Channel.UNLIMITED)
            activeChannel = channel
            opened.countDown()
            return flow {
                for (event in channel) {
                    emit(event)
                }
            }.onCompletion { cause ->
                if (cause is CancellationException) {
                    canceled.countDown()
                } else {
                    completed.set(true)
                }
            }
        }

        fun enqueue(vararg events: ChatTurnEvent) {
            activeChannel?.let { channel ->
                events.forEach { channel.trySend(it) }
            }
        }

        fun complete() {
            activeChannel?.close()
        }
    }

    // ------------------------------------------------------------------
    // The run-stop slice of the chat screen. All IDs are
    // synthetic; no network, no credentials, no real server.
    // ------------------------------------------------------------------

    @Test
    fun stopControlIsInvisibleBeforeRunStartedAndGoneAfterTerminalEvent() {
        val stopper = FakeRunStopRequester()
        val source = FakeTurnSource()
        setScreen(history(emptyList()), source, runStopRequester = stopper)

        waitForText(context.getString(R.string.chat_empty))
        composeRule.onNodeWithText(context.getString(R.string.chat_input_label)).performTextInput("Stop-Test")
        ImeClickSettle.settleAfterTyping(composeRule)
        composeRule.onNodeWithText(context.getString(R.string.chat_send)).performClick()
        assertTrue(source.opened.await(3, TimeUnit.SECONDS))

        // Before run.started the stop control is invisible; a tap cannot
        // reach the stop POST.
        assertTrue(
            "stop control must be invisible before RunStarted",
            composeRule.onAllNodesWithText(context.getString(R.string.chat_stop)).fetchSemanticsNodes().isEmpty()
        )
        assertEquals(0, stopper.calls.get())

        source.enqueue(ChatTurnEvent.RunStarted(RUN_ID))
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(context.getString(R.string.chat_stop)).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(context.getString(R.string.chat_stop)).assertIsEnabled()

        // The terminal stream state ends the turn; afterwards the
        // stop control is invisible again.
        source.enqueue(ChatTurnEvent.TurnCompleted())
        source.complete()
        waitForFlowCompletion(source)
        assertTrue(
            "stop control must be invisible after the terminal event",
            composeRule.onAllNodesWithText(context.getString(R.string.chat_stop)).fetchSemanticsNodes().isEmpty()
        )
    }

    @Test
    fun doubleTapOnStopSendsExactlyOneRequestAndShowsStopping() {
        val stopper = FakeRunStopRequester()
        val source = FakeTurnSource()
        setScreen(history(emptyList()), source, runStopRequester = stopper)

        waitForText(context.getString(R.string.chat_empty))
        composeRule.onNodeWithText(context.getString(R.string.chat_input_label)).performTextInput("Stop-Doppeltipp")
        ImeClickSettle.settleAfterTyping(composeRule)
        composeRule.onNodeWithText(context.getString(R.string.chat_send)).performClick()
        source.enqueue(ChatTurnEvent.RunStarted(RUN_ID))
        waitForText(context.getString(R.string.chat_stop))

        composeRule.onNodeWithText(context.getString(R.string.chat_stop)).performClick()
        assertTrue("stop request must start", stopper.started.await(3, TimeUnit.SECONDS))
        // Quick second tap while the request is running: the control is
        // disabled and there is exactly one call.
        composeRule
            .onNodeWithText(context.getString(R.string.chat_stopping))
            .assertIsDisplayed()
            .assertIsNotEnabled()
        composeRule.onNodeWithText(context.getString(R.string.chat_stopping)).performClick()
        assertEquals("exactly one stop request", 1, stopper.calls.get())
        assertEquals(RUN_ID, stopper.runId.get())
        assertEquals("fixture-profile-id", stopper.profileId.get())

        // The stream continues (no local cancellation): the cancellation latch
        // is still intact (1); only the terminal event ends the collector.
        assertEquals("stream not interrupted by the stop", 1L, source.canceled.count)
        stopper.release(RunStopResult.Accepted(RUN_ID))
        source.enqueue(ChatTurnEvent.TurnCompleted())
        source.complete()
        waitForFlowCompletion(source)
        assertTrue(
            "stop control must be gone after the terminal event",
            composeRule.onAllNodesWithText(context.getString(R.string.chat_stopping)).fetchSemanticsNodes().isEmpty()
        )
        assertEquals(1, stopper.calls.get())
    }

    @Test
    fun acceptedKeepsStoppingLabelUntilTheTerminalStreamEvent() {
        val stopper = FakeRunStopRequester()
        val source = FakeTurnSource()
        setScreen(history(emptyList()), source, runStopRequester = stopper)

        waitForText(context.getString(R.string.chat_empty))
        composeRule.onNodeWithText(context.getString(R.string.chat_input_label)).performTextInput("Stop-Accepted")
        ImeClickSettle.settleAfterTyping(composeRule)
        composeRule.onNodeWithText(context.getString(R.string.chat_send)).performClick()
        source.enqueue(ChatTurnEvent.RunStarted(RUN_ID))
        waitForText(context.getString(R.string.chat_stop))

        stopper.gate = Channel(Channel.UNLIMITED)
        composeRule.onNodeWithText(context.getString(R.string.chat_stop)).performClick()
        assertTrue(stopper.started.await(3, TimeUnit.SECONDS))
        composeRule.waitUntil(timeoutMillis = 5_000) {
            try {
                composeRule.onNodeWithText(context.getString(R.string.chat_stopping)).assertIsDisplayed()
                true
            } catch (_: AssertionError) {
                false
            }
        }

        // Accepted: only "Stop angenommen" — no optimistic
        // terminal state, the composer stays blocked and the stream is not
        // cancelled locally.
        stopper.gate!!.trySend(RunStopResult.Accepted(RUN_ID))
        composeRule.waitForIdle()
        composeRule.onNodeWithText(context.getString(R.string.chat_stopping)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.chat_input_label)).assertIsNotEnabled()
        composeRule.onNodeWithText(context.getString(R.string.chat_send)).assertIsNotEnabled()
        // The collector keeps running unchanged (cancellation latch intact = 1);
        // only the terminal stream event ends it.
        assertEquals("stream must not be interrupted locally", 1L, source.canceled.count)
        assertEquals(1, source.calls.get())
        assertEquals(1, stopper.calls.get())

        // Only the existing terminal stream state ends the turn and removes
        // the stop control.
        source.enqueue(ChatTurnEvent.TurnCompleted())
        source.complete()
        waitForFlowCompletion(source)
        composeRule.onNodeWithText(context.getString(R.string.chat_input_label)).assertIsEnabled()
        assertTrue(
            "stop control must be gone after the terminal event",
            composeRule.onAllNodesWithText(context.getString(R.string.chat_stopping)).fetchSemanticsNodes().isEmpty()
        )
        // The run ID never appears in the UI.
        assertTrue(
            "run id must not be rendered",
            composeRule.onAllNodesWithText(RUN_ID).fetchSemanticsNodes().isEmpty()
        )
    }

    @Test
    fun stopFailureShowsGenericMessageAndAllowsOneManualRetry() {
        val stopper = FakeRunStopRequester()
        val source = FakeTurnSource()
        setScreen(history(emptyList()), source, runStopRequester = stopper)

        waitForText(context.getString(R.string.chat_empty))
        composeRule.onNodeWithText(context.getString(R.string.chat_input_label)).performTextInput("Stop-Fehler")
        ImeClickSettle.settleAfterTyping(composeRule)
        composeRule.onNodeWithText(context.getString(R.string.chat_send)).performClick()
        source.enqueue(ChatTurnEvent.RunStarted(RUN_ID))
        waitForText(context.getString(R.string.chat_stop))

        stopper.gate = Channel(Channel.UNLIMITED)
        composeRule.onNodeWithText(context.getString(R.string.chat_stop)).performClick()
        assertTrue(stopper.started.await(3, TimeUnit.SECONDS))
        // Generic local message without dynamic details; the running stream
        // collection stays unchanged; another manual attempt is possible
        // again.
        stopper.gate!!.trySend(RunStopResult.Failure(RunStopError.Network))
        composeRule.waitUntil(timeoutMillis = 5_000) {
            try {
                composeRule
                    .onNodeWithText(context.getString(R.string.error_run_stop_generic))
                    .assertIsDisplayed()
                true
            } catch (_: AssertionError) {
                false
            }
        }
        assertTrue(
            "no dynamic detail or run id in the failure message",
            composeRule.onAllNodesWithText(RUN_ID).fetchSemanticsNodes().isEmpty()
        )
        assertEquals("running stream must stay untouched", 1L, source.canceled.count)
        assertEquals(1, stopper.calls.get())
        composeRule.onNodeWithText(context.getString(R.string.chat_stop)).assertIsEnabled()

        // The deliberate manual retry sends exactly one further request.
        stopper.gate = Channel(Channel.UNLIMITED)
        composeRule.onNodeWithText(context.getString(R.string.chat_stop)).performClick()
        assertTrue(stopper.started.await(3, TimeUnit.SECONDS))
        assertEquals("manual retry sends one further request", 2, stopper.calls.get())
        stopper.gate!!.trySend(RunStopResult.Accepted(RUN_ID))

        source.enqueue(ChatTurnEvent.TurnCompleted())
        source.complete()
        waitForFlowCompletion(source)
        assertEquals("no further stop request after the terminal event", 2, stopper.calls.get())
    }

    @Test
    fun alreadyTerminalTriggersHistoryRefreshOnlyAndNeverResendsTheChatPost() {
        val historyCalls = AtomicInteger()
        val stopper = FakeRunStopRequester()
        val source = FakeTurnSource()
        setScreen(
            MessageHistorySource { _, _ ->
                historyCalls.incrementAndGet()
                MessageHistoryResult.Success(MessagePage(emptyList()))
            },
            source,
            runStopRequester = stopper
        )

        waitForText(context.getString(R.string.chat_empty))
        composeRule.onNodeWithText(context.getString(R.string.chat_input_label)).performTextInput("Stop-AlreadyTerminal")
        ImeClickSettle.settleAfterTyping(composeRule)
        composeRule.onNodeWithText(context.getString(R.string.chat_send)).performClick()
        source.enqueue(ChatTurnEvent.RunStarted(RUN_ID))
        waitForText(context.getString(R.string.chat_stop))
        assertEquals(1, historyCalls.get())

        stopper.gate = Channel(Channel.UNLIMITED)
        composeRule.onNodeWithText(context.getString(R.string.chat_stop)).performClick()
        assertTrue(stopper.started.await(3, TimeUnit.SECONDS))
        stopper.gate!!.trySend(RunStopResult.AlreadyTerminal(RUN_ID))

        // The existing history-refresh/reconciliation path is triggered:
        // exactly one additional idempotent history read; the chat POST is
        // never resent.
        composeRule.waitUntil(timeoutMillis = 5_000) { historyCalls.get() == 2 }
        assertEquals(2, historyCalls.get())
        assertEquals("no chat resending", 1, source.calls.get())
        assertEquals("no second stop request", 1, stopper.calls.get())
        composeRule.waitForIdle()
        // No stop control any more; the composer is free for a new run.
        assertTrue(
            "stop control must be gone after reconciliation",
            composeRule.onAllNodesWithText(context.getString(R.string.chat_stop)).fetchSemanticsNodes().isEmpty()
        )
        composeRule.onNodeWithText(context.getString(R.string.chat_input_label)).assertIsEnabled()
    }

    @Test
    fun newRunResetsStopStateAndBindsOnlyItsOwnRunId() {
        val stopper = FakeRunStopRequester()
        val source = FakeTurnSource()
        setScreen(history(emptyList()), source, runStopRequester = stopper)

        waitForText(context.getString(R.string.chat_empty))
        // First run: stop fails (generic). The fake has no gate here, so the
        // result is prepared synchronously before the tap, so that the
        // (ungated) call returns it.
        composeRule.onNodeWithText(context.getString(R.string.chat_input_label)).performTextInput("Erster Lauf")
        ImeClickSettle.settleAfterTyping(composeRule)
        composeRule.onNodeWithText(context.getString(R.string.chat_send)).performClick()
        source.enqueue(ChatTurnEvent.RunStarted(RUN_ID))
        waitForText(context.getString(R.string.chat_stop))
        stopper.release(RunStopResult.Failure(RunStopError.Network))
        composeRule.onNodeWithText(context.getString(R.string.chat_stop)).performClick()
        assertTrue(stopper.started.await(3, TimeUnit.SECONDS))
        composeRule.waitUntil(timeoutMillis = 5_000) {
            try {
                composeRule
                    .onNodeWithText(context.getString(R.string.error_run_stop_generic))
                    .assertIsDisplayed()
                true
            } catch (_: AssertionError) {
                false
            }
        }
        assertEquals(1, stopper.calls.get())

        // Clean terminal completion of the first run: stop state (incl.
        // failure) and bound run ID are reset and the composer becomes free.
        source.enqueue(ChatTurnEvent.TurnCompleted())
        source.complete()
        waitForFlowCompletion(source)
        composeRule.onNodeWithText(context.getString(R.string.chat_input_label)).assertIsEnabled()

        // Second run: the stop state of the first run is gone and only the
        // run ID of the new run is bound.
        stopper.release(RunStopResult.Accepted(NEW_RUN_ID))
        composeRule.onNodeWithText(context.getString(R.string.chat_input_label)).performTextInput("Zweiter Lauf")
        ImeClickSettle.settleAfterTyping(composeRule)
        composeRule.onNodeWithText(context.getString(R.string.chat_send)).performClick()
        assertEquals(2, source.calls.get())
        source.enqueue(ChatTurnEvent.RunStarted(NEW_RUN_ID))
        waitForText(context.getString(R.string.chat_stop))
        composeRule.onNodeWithText(context.getString(R.string.chat_stop)).performClick()
        assertTrue(stopper.started.await(3, TimeUnit.SECONDS))
        assertEquals("new run binds only its own run id", NEW_RUN_ID, stopper.runId.get())
        assertEquals("second stop is exactly one further call", 2, stopper.calls.get())
        // The old run ID must not appear anywhere (any more).
        assertTrue(
            "old run id must not appear anywhere",
            composeRule.onAllNodesWithText(RUN_ID).fetchSemanticsNodes().isEmpty()
        )

        source.enqueue(ChatTurnEvent.TurnCompleted())
        source.complete()
        waitForFlowCompletion(source)
        assertEquals("no further stop after the second terminal event", 2, stopper.calls.get())
    }

    @Test
    fun backWhileStopRequestIsInFlightKeepsRunAndStreamAlive() {
        val stopper = FakeRunStopRequester()
        val source = FakeTurnSource()
        val backInvoked = CountDownLatch(1)
        setScreen(
            history(emptyList()),
            source,
            onBack = { backInvoked.countDown() },
            runStopRequester = stopper
        )

        waitForText(context.getString(R.string.chat_empty))
        composeRule.onNodeWithText(context.getString(R.string.chat_input_label)).performTextInput("Stop-Back")
        ImeClickSettle.settleAfterTyping(composeRule)
        composeRule.onNodeWithText(context.getString(R.string.chat_send)).performClick()
        source.enqueue(ChatTurnEvent.RunStarted(RUN_ID))
        waitForText(context.getString(R.string.chat_stop))

        // The stop call stays suspended in the request.
        stopper.gate = Channel(Channel.UNLIMITED)
        composeRule.onNodeWithText(context.getString(R.string.chat_stop)).performClick()
        assertTrue(stopper.started.await(3, TimeUnit.SECONDS))
        composeRule.waitUntil(timeoutMillis = 5_000) {
            try {
                composeRule.onNodeWithText(context.getString(R.string.chat_stopping)).assertIsDisplayed()
                true
            } catch (_: AssertionError) {
                false
            }
        }

        // Background continuity: back is synchronous and cancels nothing —
        // neither the run nor the in-flight stop request.
        composeRule.onNodeWithText(context.getString(R.string.chat_back)).performClick()
        assertTrue("onBack was not invoked", backInvoked.await(3, TimeUnit.SECONDS))
        assertFalse(
            "the in-flight stop coroutine must not be canceled by back",
            stopper.canceled.await(100, TimeUnit.MILLISECONDS)
        )
        assertFalse(
            "the running stream must stay alive after back",
            source.canceled.await(100, TimeUnit.MILLISECONDS)
        )
        assertEquals("exactly one stop request in total", 1, stopper.calls.get())
        assertEquals(1, source.calls.get())

        // The stop request runs to completion; the accepted result alone does
        // not end the turn (no optimistic terminal state).
        stopper.gate!!.trySend(RunStopResult.Accepted(RUN_ID))
        composeRule.waitForIdle()

        // Only the terminal stream event ends the turn: the stop control
        // disappears and the composer becomes free.
        source.enqueue(ChatTurnEvent.TurnCompleted())
        source.complete()
        waitForFlowCompletion(source)
        assertTrue(
            "stop control must be gone after the terminal event",
            composeRule.onAllNodesWithText(context.getString(R.string.chat_stopping)).fetchSemanticsNodes().isEmpty()
        )
        composeRule.onNodeWithText(context.getString(R.string.chat_input_label)).assertIsEnabled()
    }

    private companion object {
        const val RUN_ID = "run_synthetic"
        const val NEW_RUN_ID = "run_synthetic_2"
        val APPROVAL_TITLE: String by lazy { InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.approval_title) }
        // Stable test tags of the uncertain-run notice and its manual
        // status-recovery action (mirroring ChatScreen's TAG_RUN_UNCERTAIN /
        // TAG_RUN_RECONCILE).
        const val UNCERTAIN_TAG = "chat_run_uncertain"
        const val RECONCILE_TAG = "chat_run_reconcile"
        // The exact generic conflict text (no IDs, no server detail).
        val CONFLICT_TEXT: String by lazy {
            InstrumentationRegistry.getInstrumentation().targetContext
                .getString(R.string.chat_run_conflict)
        }
        const val APPROVAL_RUN_ID = "run_0123456789abcdef0123456789abcdef"
        val APPROVAL_REQUEST = ApprovalRequest(
            runId = APPROVAL_RUN_ID,
            requestId = "fedcba9876543210fedcba9876543210",
            description = ApprovalRequest.SAFE_DESCRIPTION
        )
        val SECOND_APPROVAL_REQUEST = ApprovalRequest(
            runId = APPROVAL_RUN_ID,
            requestId = "00112233445566778899aabbccddeeff",
            description = ApprovalRequest.SAFE_DESCRIPTION
        )
    }

    // ------------------------------------------------------------------
    // The run-steer slice of the chat screen. All IDs are
    // synthetic; no network, no credentials, no real server. The tests
    // target the steer semantics/test tags (chatSteerInput,
    // chatSteerSend, chatSteeringStatus, chatSteerQueuedStatus,
    // chatSteerFailedStatus) rather than the localized texts. Each
    // recoverable block is tagged `chatSteerRecovery:<index>` and its OWN
    // "send as new message" action is tagged
    // `chatSteerSendAsNewMessage:<index>` (a unique, stable tag per item —
    // the shared label text alone is ambiguous in Compose selectors). The
    // single-item tests below therefore select `:0`.
    // ------------------------------------------------------------------

    @Test
    fun steerControlIsInvisibleBeforeRunStartedAndGoneAfterTerminalEvent() {
        val steerer = FakeRunSteerRequester()
        val source = FakeTurnSource()
        setScreen(history(emptyList()), source, runSteerRequester = steerer)

        waitForText(context.getString(R.string.chat_empty))
        composeRule.onNodeWithText(context.getString(R.string.chat_input_label)).performTextInput("Steuer-Sichtbarkeit")
        ImeClickSettle.settleAfterTyping(composeRule)
        composeRule.onNodeWithText(context.getString(R.string.chat_send)).performClick()
        assertTrue(source.opened.await(3, TimeUnit.SECONDS))

        // Before run.started the steer input is invisible; a tap cannot
        // reach the steer POST.
        assertTrue(
            "steer input must be invisible before RunStarted",
            composeRule.onAllNodesWithTag("chatSteerInput").fetchSemanticsNodes().isEmpty()
        )
        assertEquals(0, steerer.calls.get())

        source.enqueue(ChatTurnEvent.RunStarted(RUN_ID))
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("chatSteerInput").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("chatSteerSend").assertIsEnabled()

        // The terminal stream state ends the turn; afterwards the steer
        // input is invisible again.
        source.enqueue(ChatTurnEvent.TurnCompleted())
        source.complete()
        waitForFlowCompletion(source)
        assertTrue(
            "steer input must be invisible after the terminal event",
            composeRule.onAllNodesWithTag("chatSteerInput").fetchSemanticsNodes().isEmpty()
        )
    }

    @Test
    fun steerSubmitQueuedShowsAckClearsInputAndKeepsTheRunActive() {
        val steerer = FakeRunSteerRequester()
        val source = FakeTurnSource()
        steerer.gate = Channel(Channel.UNLIMITED)
        setScreen(history(emptyList()), source, runSteerRequester = steerer)

        waitForText(context.getString(R.string.chat_empty))
        composeRule.onNodeWithText(context.getString(R.string.chat_input_label)).performTextInput("Steuer-Queued")
        ImeClickSettle.settleAfterTyping(composeRule)
        composeRule.onNodeWithText(context.getString(R.string.chat_send)).performClick()
        source.enqueue(ChatTurnEvent.RunStarted(RUN_ID))
        composeRule.onNodeWithTag("chatSteerInput").performTextInput("Kurs nach links")
        ImeClickSettle.settleAfterTyping(composeRule)
        composeRule.onNodeWithTag("chatSteerSend").performClick()
        assertTrue("steer request must start", steerer.started.await(3, TimeUnit.SECONDS))
        // Gated: the request parameters are set before the result is
        // delivered — no race when checking the exact text.
        assertEquals(RUN_ID, steerer.runId.get())
        assertEquals("fixture-profile-id", steerer.profileId.get())
        // Exact, trimmed user text — no other character sequence.
        assertEquals("Kurs nach links", steerer.text.get())
        steerer.gate!!.trySend(RunSteerResult.Queued(RUN_ID))

        // Queued: short confirmation, steer input cleared, the run stays
        // active — no local terminal state, no new chat message.
        composeRule.waitUntil(timeoutMillis = 5_000) {
            try {
                composeRule.onNodeWithTag("chatSteerQueuedStatus").assertIsDisplayed()
                true
            } catch (_: AssertionError) {
                false
            }
        }
        assertTrue("steer input must be cleared after queued", steerDraftText().isNullOrBlank())
        assertEquals("no new chat message from a steer", 1, source.calls.get())
        // The run is still active: composer and send are blocked.
        composeRule.onNodeWithText(context.getString(R.string.chat_input_label)).assertIsNotEnabled()
        composeRule.onNodeWithText(context.getString(R.string.chat_send)).assertIsNotEnabled()
        // The run ID never appears in the UI.
        assertTrue(
            "run id must not be rendered",
            composeRule.onAllNodesWithText(RUN_ID).fetchSemanticsNodes().isEmpty()
        )

        // Only the terminal stream event ends the turn.
        source.enqueue(ChatTurnEvent.TurnCompleted())
        source.complete()
        waitForFlowCompletion(source)
        assertTrue(
            "steer control must be gone after the terminal event",
            composeRule.onAllNodesWithTag("chatSteerInput").fetchSemanticsNodes().isEmpty()
        )
    }

    @Test
    fun steerNotAcceptingPreservesTextAndNeverAutoSendsIt() {
        val steerer = FakeRunSteerRequester()
        val source = FakeTurnSource()
        steerer.gate = Channel(Channel.UNLIMITED)
        setScreen(history(emptyList()), source, runSteerRequester = steerer)

        waitForText(context.getString(R.string.chat_empty))
        composeRule.onNodeWithText(context.getString(R.string.chat_input_label)).performTextInput("Steuer-409")
        ImeClickSettle.settleAfterTyping(composeRule)
        composeRule.onNodeWithText(context.getString(R.string.chat_send)).performClick()
        source.enqueue(ChatTurnEvent.RunStarted(RUN_ID))
        composeRule.onNodeWithTag("chatSteerInput").performTextInput("Kurs nach rechts")
        ImeClickSettle.settleAfterTyping(composeRule)
        composeRule.onNodeWithTag("chatSteerSend").performClick()
        assertTrue(steerer.started.await(3, TimeUnit.SECONDS))
        assertEquals("Kurs nach rechts", steerer.text.get())
        steerer.gate!!.trySend(RunSteerResult.NotAccepting(RUN_ID))

        // 409: the exact text stays in the recoverable state set; the
        // explicit action appears.
        composeRule.waitUntil(timeoutMillis = 5_000) {
            try {
                composeRule.onNodeWithTag("chatSteerSendAsNewMessage:0").assertIsDisplayed()
                true
            } catch (_: AssertionError) {
                false
            }
        }
        assertTrue("steer input must be empty after 409", steerDraftText().isNullOrBlank())

        // Exactly one preserved item: the title carries NO counter.
        composeRule.onNodeWithText(context.getString(R.string.chat_steer_recovery_title))
            .assertIsDisplayed()
        assertTrue(
            "a single recovery item must not render an index counter",
            composeRule
                .onAllNodesWithText(
                    context.getString(R.string.chat_steer_recovery_title_counter, 1, 1)
                )
                .fetchSemanticsNodes().isEmpty()
        )

        // The explicit action moves the text into the ordinary composer —
        // without sending it.
        composeRule.onNodeWithTag("chatSteerSendAsNewMessage:0").performClick()
        assertEquals("Kurs nach rechts", draftText())
        assertTrue(
            "recovery block must be gone after the explicit action",
            composeRule.onAllNodesWithTag("chatSteerSendAsNewMessage:0")
                .fetchSemanticsNodes().isEmpty()
        )
        assertEquals("the text must never be auto-sent", 1, source.calls.get())

        // The run is still running: the composer is blocked, but the text is
        // preserved. It is only sendable after the terminal event.
        composeRule.onNodeWithText(context.getString(R.string.chat_input_label)).assertIsNotEnabled()
        source.enqueue(ChatTurnEvent.TurnCompleted())
        source.complete()
        waitForFlowCompletion(source)
        composeRule.onNodeWithText(context.getString(R.string.chat_input_label)).assertIsEnabled()
        assertEquals("Kurs nach rechts", draftText())
        assertEquals("still not auto-sent after completion", 1, source.calls.get())
    }

    @Test
    fun pendingSteerFromRunCompletedIsRecoveredThroughTheSameActionWithoutAutoSend() {
        val source = FakeTurnSource()
        setScreen(history(emptyList()), source, runSteerRequester = FakeRunSteerRequester())

        waitForText(context.getString(R.string.chat_empty))
        composeRule.onNodeWithText(context.getString(R.string.chat_input_label)).performTextInput("Pending-Steer")
        ImeClickSettle.settleAfterTyping(composeRule)
        composeRule.onNodeWithText(context.getString(R.string.chat_send)).performClick()
        source.enqueue(ChatTurnEvent.RunStarted(RUN_ID))
        source.enqueue(ChatTurnEvent.TurnCompleted(pendingSteer = "Weiter mit Plan B"))
        source.complete()
        waitForFlowCompletion(source)

        // The turn has ended: the recovery action appears through exactly the
        // same UI as after a 409.
        composeRule.waitUntil(timeoutMillis = 5_000) {
            try {
                composeRule.onNodeWithTag("chatSteerSendAsNewMessage:0").assertIsDisplayed()
                true
            } catch (_: AssertionError) {
                false
            }
        }
        composeRule.onNodeWithText(context.getString(R.string.chat_input_label)).assertIsEnabled()

        // Explicit action: text moves into the composer, without sending.
        composeRule.onNodeWithTag("chatSteerSendAsNewMessage:0").performClick()
        assertEquals("Weiter mit Plan B", draftText())
        assertTrue(
            "recovery block must be gone after the explicit action",
            composeRule.onAllNodesWithTag("chatSteerSendAsNewMessage:0")
                .fetchSemanticsNodes().isEmpty()
        )
        assertEquals("the pending steer must never be auto-sent", 1, source.calls.get())
    }

    @Test
    fun doubleTapOnSteerSendsExactlyOneRequestAndDisablesTheControl() {
        val steerer = FakeRunSteerRequester()
        val source = FakeTurnSource()
        steerer.gate = Channel(Channel.UNLIMITED)
        setScreen(history(emptyList()), source, runSteerRequester = steerer)

        waitForText(context.getString(R.string.chat_empty))
        composeRule.onNodeWithText(context.getString(R.string.chat_input_label)).performTextInput("Steuer-Doppeltipp")
        ImeClickSettle.settleAfterTyping(composeRule)
        composeRule.onNodeWithText(context.getString(R.string.chat_send)).performClick()
        source.enqueue(ChatTurnEvent.RunStarted(RUN_ID))
        composeRule.onNodeWithTag("chatSteerInput").performTextInput("Doppeltipp")
        ImeClickSettle.settleAfterTyping(composeRule)
        composeRule.onNodeWithTag("chatSteerSend").performClick()
        assertTrue(steerer.started.await(3, TimeUnit.SECONDS))

        // In the request: input and action are disabled; the quick second
        // tap opens no second request.
        composeRule.onNodeWithTag("chatSteeringStatus").assertIsDisplayed()
        composeRule.onNodeWithTag("chatSteerInput").assertIsNotEnabled()
        composeRule.onNodeWithTag("chatSteerSend").assertIsNotEnabled()
        composeRule.onNodeWithTag("chatSteerSend").performClick()
        assertEquals("exactly one steer request", 1, steerer.calls.get())

        steerer.gate!!.trySend(RunSteerResult.Queued(RUN_ID))
        composeRule.waitUntil(timeoutMillis = 5_000) {
            try {
                composeRule.onNodeWithTag("chatSteerQueuedStatus").assertIsDisplayed()
                true
            } catch (_: AssertionError) {
                false
            }
        }
        assertEquals("still exactly one steer request", 1, steerer.calls.get())
    }

    @Test
    fun steerFailureKeepsTheTextAndAllowsOneManualRetry() {
        val steerer = FakeRunSteerRequester()
        val source = FakeTurnSource()
        steerer.release(RunSteerResult.Failure(RunSteerError.Network))
        setScreen(history(emptyList()), source, runSteerRequester = steerer)

        waitForText(context.getString(R.string.chat_empty))
        composeRule.onNodeWithText(context.getString(R.string.chat_input_label)).performTextInput("Steuer-Fehler")
        ImeClickSettle.settleAfterTyping(composeRule)
        composeRule.onNodeWithText(context.getString(R.string.chat_send)).performClick()
        source.enqueue(ChatTurnEvent.RunStarted(RUN_ID))
        composeRule.onNodeWithTag("chatSteerInput").performTextInput("Kursversuch")
        ImeClickSettle.settleAfterTyping(composeRule)
        composeRule.onNodeWithTag("chatSteerSend").performClick()
        assertTrue(steerer.started.await(3, TimeUnit.SECONDS))

        // Generic message without dynamic details; the text stays in the
        // input for a deliberate manual retry; the run ID is rendered
        // nowhere.
        composeRule.waitUntil(timeoutMillis = 5_000) {
            try {
                composeRule.onNodeWithTag("chatSteerFailedStatus").assertIsDisplayed()
                true
            } catch (_: AssertionError) {
                false
            }
        }
        assertEquals("Kursversuch", steerDraftText())
        assertTrue(
            "no run id in the failure state",
            composeRule.onAllNodesWithText(RUN_ID).fetchSemanticsNodes().isEmpty()
        )

        // The deliberate manual retry sends exactly one further request
        // (same text).
        composeRule.onNodeWithTag("chatSteerSend").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { steerer.calls.get() == 2 }
        assertEquals("manual retry sends one further request", 2, steerer.calls.get())
        assertEquals("Kursversuch", steerer.text.get())
    }

    @Test
    fun newNormalSendStartsTheNewRunWithFreshSteerStateButKeepsThePreservedText() {
        val steerer = FakeRunSteerRequester()
        val source = FakeTurnSource()
        steerer.release(RunSteerResult.NotAccepting(RUN_ID))
        setScreen(history(emptyList()), source, runSteerRequester = steerer)

        waitForText(context.getString(R.string.chat_empty))
        composeRule.onNodeWithText(context.getString(R.string.chat_input_label)).performTextInput("Erster Lauf")
        ImeClickSettle.settleAfterTyping(composeRule)
        composeRule.onNodeWithText(context.getString(R.string.chat_send)).performClick()
        source.enqueue(ChatTurnEvent.RunStarted(RUN_ID))
        composeRule.onNodeWithTag("chatSteerInput").performTextInput("Alter Steuerentwurf")
        ImeClickSettle.settleAfterTyping(composeRule)
        composeRule.onNodeWithTag("chatSteerSend").performClick()
        assertTrue(steerer.started.await(3, TimeUnit.SECONDS))
        // 409: recovery text set.
        composeRule.waitUntil(timeoutMillis = 5_000) {
            try {
                composeRule.onNodeWithTag("chatSteerSendAsNewMessage:0").assertIsDisplayed()
                true
            } catch (_: AssertionError) {
                false
            }
        }
        source.enqueue(ChatTurnEvent.TurnCompleted())
        source.complete()
        waitForFlowCompletion(source)

        // A new ordinary message starts a new run: the run-specific steer
        // draft must not slip into this run — but the preserved recovery text
        // is user input and is not lost.
        composeRule.onNodeWithText(context.getString(R.string.chat_input_label)).performTextInput("Zweiter Lauf")
        ImeClickSettle.settleAfterTyping(composeRule)
        composeRule.onNodeWithText(context.getString(R.string.chat_send)).performClick()
        assertEquals(2, source.calls.get())
        source.enqueue(ChatTurnEvent.RunStarted(NEW_RUN_ID))
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("chatSteerInput").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(
            "the preserved recovery text must survive a new normal send",
            composeRule.onAllNodesWithTag("chatSteerSendAsNewMessage:0")
                .fetchSemanticsNodes().isNotEmpty()
        )
        assertTrue("stale steer draft must be cleared", steerDraftText().isNullOrBlank())
        assertEquals("no steer request for the new run", 1, steerer.calls.get())

        // Explicit action: exact text into the composer, without sending.
        composeRule.onNodeWithTag("chatSteerSendAsNewMessage:0").performClick()
        assertEquals("Alter Steuerentwurf", draftText())
        assertEquals("the preserved text must never be auto-sent", 2, source.calls.get())
    }

    @Test
    fun terminalWithoutPendingSteerPreservesTheNotAcceptingRecovery() {
        val steerer = FakeRunSteerRequester()
        val source = FakeTurnSource()
        steerer.release(RunSteerResult.NotAccepting(RUN_ID))
        setScreen(history(emptyList()), source, runSteerRequester = steerer)

        waitForText(context.getString(R.string.chat_empty))
        composeRule.onNodeWithText(context.getString(R.string.chat_input_label)).performTextInput("Bleibt-Stehen")
        ImeClickSettle.settleAfterTyping(composeRule)
        composeRule.onNodeWithText(context.getString(R.string.chat_send)).performClick()
        source.enqueue(ChatTurnEvent.RunStarted(RUN_ID))
        composeRule.onNodeWithTag("chatSteerInput").performTextInput("Bleibt stehen")
        ImeClickSettle.settleAfterTyping(composeRule)
        composeRule.onNodeWithTag("chatSteerSend").performClick()
        assertTrue(steerer.started.await(3, TimeUnit.SECONDS))
        composeRule.waitUntil(timeoutMillis = 5_000) {
            try {
                composeRule.onNodeWithTag("chatSteerSendAsNewMessage:0").assertIsDisplayed()
                true
            } catch (_: AssertionError) {
                false
            }
        }

        // Terminal without `pending_steer`: the user text must not be lost —
        // the recovery action stays visible.
        source.enqueue(ChatTurnEvent.TurnCompleted())
        source.complete()
        waitForFlowCompletion(source)
        composeRule.waitUntil(timeoutMillis = 5_000) {
            try {
                composeRule.onNodeWithTag("chatSteerSendAsNewMessage:0").assertIsDisplayed()
                true
            } catch (_: AssertionError) {
                false
            }
        }
        // The explicit action moves the text without sending it.
        composeRule.onNodeWithTag("chatSteerSendAsNewMessage:0").performClick()
        assertEquals("Bleibt stehen", draftText())
        assertEquals(1, source.calls.get())
    }

    @Test
    fun inFlightSteerDraftIsPreservedAsRecoveryWhenTheRunEnds() {
        val steerer = FakeRunSteerRequester()
        val source = FakeTurnSource()
        steerer.gate = Channel(Channel.UNLIMITED)
        setScreen(history(emptyList()), source, runSteerRequester = steerer)

        waitForText(context.getString(R.string.chat_empty))
        composeRule.onNodeWithText(context.getString(R.string.chat_input_label)).performTextInput("Ende-in-Flight")
        ImeClickSettle.settleAfterTyping(composeRule)
        composeRule.onNodeWithText(context.getString(R.string.chat_send)).performClick()
        source.enqueue(ChatTurnEvent.RunStarted(RUN_ID))
        composeRule.onNodeWithTag("chatSteerInput").performTextInput("Halb weg")
        ImeClickSettle.settleAfterTyping(composeRule)
        composeRule.onNodeWithTag("chatSteerSend").performClick()
        assertTrue(steerer.started.await(3, TimeUnit.SECONDS))

        // The run ends while the steer request is still running: the server
        // may or may not have queued the text — it must not be lost under any
        // circumstances, but moves into the recovery state set.
        source.enqueue(ChatTurnEvent.TurnCompleted())
        source.complete()
        waitForFlowCompletion(source)
        composeRule.waitUntil(timeoutMillis = 5_000) {
            try {
                composeRule.onNodeWithTag("chatSteerSendAsNewMessage:0").assertIsDisplayed()
                true
            } catch (_: AssertionError) {
                false
            }
        }
        // Explicit action: exact text into the composer, without sending.
        composeRule.onNodeWithTag("chatSteerSendAsNewMessage:0").performClick()
        assertEquals("Halb weg", draftText())
        assertEquals("the preserved draft must never be auto-sent", 1, source.calls.get())
    }

    @Test
    fun recoveryActionIsDisabledAndNoOpWhileTheOrdinaryComposerIsNonblank() {
        val steerer = FakeRunSteerRequester()
        val source = FakeTurnSource()
        steerer.release(RunSteerResult.NotAccepting(RUN_ID))
        setScreen(history(emptyList()), source, runSteerRequester = steerer)

        waitForText(context.getString(R.string.chat_empty))
        composeRule.onNodeWithText(context.getString(R.string.chat_input_label)).performTextInput("Vier-Hunderteinundneunzig")
        ImeClickSettle.settleAfterTyping(composeRule)
        composeRule.onNodeWithText(context.getString(R.string.chat_send)).performClick()
        source.enqueue(ChatTurnEvent.RunStarted(RUN_ID))
        composeRule.onNodeWithTag("chatSteerInput").performTextInput("Rettet mich")
        ImeClickSettle.settleAfterTyping(composeRule)
        composeRule.onNodeWithTag("chatSteerSend").performClick()
        assertTrue(steerer.started.await(3, TimeUnit.SECONDS))
        composeRule.waitUntil(timeoutMillis = 5_000) {
            try {
                composeRule.onNodeWithTag("chatSteerSendAsNewMessage:0").assertIsDisplayed()
                true
            } catch (_: AssertionError) {
                false
            }
        }
        // End the turn so that the ordinary composer is editable again.
        source.enqueue(ChatTurnEvent.TurnCompleted())
        source.complete()
        waitForFlowCompletion(source)

        // The ordinary composer receives a non-empty draft: the recovery
        // action must be disabled and remain a no-op, so that the user text
        // is not overwritten.
        composeRule.onNodeWithText(context.getString(R.string.chat_input_label)).performTextInput("Mein laufender Entwurf")
        assertEquals("Mein laufender Entwurf", draftText())
        composeRule.onNodeWithTag("chatSteerSendAsNewMessage:0").assertIsNotEnabled()
        composeRule.onNodeWithText(context.getString(R.string.chat_steer_recovery_clear_draft_hint))
            .assertIsDisplayed()

        // Clicking the (disabled) action changes nothing: the draft stays,
        // the recovery text is preserved, the block stays visible.
        composeRule.onNodeWithTag("chatSteerSendAsNewMessage:0").performClick()
        assertEquals("der Composer darf nicht überschrieben werden", "Mein laufender Entwurf", draftText())
        assertEquals("genau ein Item", 1, recoveryItemCount())
        assertTrue("der erhaltene Text muss erhalten bleiben", recoveryItemTextPresent("Rettet mich"))

        // Only when the composer is empty is the action possible again and
        // moves exactly the one item. `performTextClearance()` really clears
        // the field (unlike `performTextInput("")`, which is a no-op),
        // exactly the precondition the action requires.
        composeRule.onNodeWithText(context.getString(R.string.chat_input_label)).performTextClearance()
        composeRule.onNodeWithTag("chatSteerSendAsNewMessage:0").assertIsEnabled()
        assertTrue(
            "the clear-draft hint must disappear once the action is enabled",
            composeRule
                .onAllNodesWithText(context.getString(R.string.chat_steer_recovery_clear_draft_hint))
                .fetchSemanticsNodes().isEmpty()
        )
        composeRule.onNodeWithTag("chatSteerSendAsNewMessage:0").performClick()
        assertEquals("Rettet mich", draftText())
        assertTrue(
            "der Recovery-Block muss nach dem Verschieben verschwinden",
            composeRule.onAllNodesWithTag("chatSteerSendAsNewMessage:0")
                .fetchSemanticsNodes().isEmpty()
        )
        assertEquals("nie automatisch gesendet", 1, source.calls.get())
    }

    @Test
    fun prior409AndDifferentPendingSteerAreTwoSeparateOrderedItems() {
        val steerer = FakeRunSteerRequester()
        val source = FakeTurnSource()
        steerer.release(RunSteerResult.NotAccepting(RUN_ID))
        setScreen(history(emptyList()), source, runSteerRequester = steerer)

        waitForText(context.getString(R.string.chat_empty))
        composeRule.onNodeWithText(context.getString(R.string.chat_input_label)).performTextInput("Zwei-Erhaltung")
        ImeClickSettle.settleAfterTyping(composeRule)
        composeRule.onNodeWithText(context.getString(R.string.chat_send)).performClick()
        source.enqueue(ChatTurnEvent.RunStarted(RUN_ID))
        composeRule.onNodeWithTag("chatSteerInput").performTextInput("Steuer-A")
        ImeClickSettle.settleAfterTyping(composeRule)
        composeRule.onNodeWithTag("chatSteerSend").performClick()
        assertTrue(steerer.started.await(3, TimeUnit.SECONDS))
        composeRule.waitUntil(timeoutMillis = 5_000) {
            try {
                composeRule.onNodeWithTag("chatSteerSendAsNewMessage:0").assertIsDisplayed()
                true
            } catch (_: AssertionError) {
                false
            }
        }
        // Terminal with a different `pending_steer`: two separate, ordered
        // items, never merged into one string.
        source.enqueue(ChatTurnEvent.TurnCompleted(pendingSteer = "Steuer-B"))
        source.complete()
        waitForFlowCompletion(source)

        assertEquals("zwei separate Items", 2, recoveryItemCount())
        assertTrue("das erste Item bleibt erhalten", recoveryItemTextPresent("Steuer-A"))
        assertTrue("das zweite Item bleibt erhalten", recoveryItemTextPresent("Steuer-B"))
        // More than one preserved item: every card title carries its counter.
        composeRule.onNodeWithText(context.getString(R.string.chat_steer_recovery_title_counter, 1, 2))
            .assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.chat_steer_recovery_title_counter, 2, 2))
            .assertIsDisplayed()
        // A merged string would have produced exactly one line
        // "Steuer-A\nSteuer-B" — that must not exist.
        assertTrue(
            "no merged single item",
            composeRule.onAllNodesWithText("Steuer-A\nSteuer-B").fetchSemanticsNodes().isEmpty()
        )
        // Two separate, ordered items: both are visible and were never
        // sent automatically.
        assertEquals("nie automatisch gesendet", 1, source.calls.get())
    }

    @Test
    fun clickingTheSecondRecoveryActionMovesExactlyTheSecondItem() {
        // Regression for action-to-item identity: each recovery block has its
        // OWN action, wired to exactly ITS position. A click on the SECOND
        // block must move exactly the second original item into the (empty)
        // composer — never the first. The first stays unchanged in the
        // recovery set; nothing is sent automatically.
        val steerer = FakeRunSteerRequester()
        val source = FakeTurnSource()
        steerer.release(RunSteerResult.NotAccepting(RUN_ID))
        setScreen(history(emptyList()), source, runSteerRequester = steerer)

        waitForText(context.getString(R.string.chat_empty))
        composeRule.onNodeWithText(context.getString(R.string.chat_input_label)).performTextInput("Zwei-Items")
        ImeClickSettle.settleAfterTyping(composeRule)
        composeRule.onNodeWithText(context.getString(R.string.chat_send)).performClick()
        source.enqueue(ChatTurnEvent.RunStarted(RUN_ID))
        composeRule.onNodeWithTag("chatSteerInput").performTextInput("Steuer-Erstes")
        ImeClickSettle.settleAfterTyping(composeRule)
        composeRule.onNodeWithTag("chatSteerSend").performClick()
        assertTrue(steerer.started.await(3, TimeUnit.SECONDS))
        composeRule.waitUntil(timeoutMillis = 5_000) {
            try {
                composeRule.onNodeWithTag("chatSteerSendAsNewMessage:0").assertIsDisplayed()
                true
            } catch (_: AssertionError) {
                false
            }
        }
        // Terminal with a second, different `pending_steer`: two ordered
        // items (0 = 409 text, 1 = pending text).
        source.enqueue(ChatTurnEvent.TurnCompleted(pendingSteer = "Steuer-Zweites"))
        source.complete()
        waitForFlowCompletion(source)

        assertEquals("zwei separate Items", 2, recoveryItemCount())
        assertTrue("das erste Item bleibt erhalten", recoveryItemTextPresent("Steuer-Erstes"))
        assertTrue("das zweite Item bleibt erhalten", recoveryItemTextPresent("Steuer-Zweites"))

        // The composer is empty, so both actions are active.
        assertTrue("Composer ist leer", draftText().isNullOrBlank())
        composeRule.onNodeWithTag("chatSteerSendAsNewMessage:1").assertIsEnabled()

        // Click on the action of the SECOND block: exactly the second
        // original text moves into the composer, the first item stays in the
        // recovery set and keeps its identity.
        composeRule.onNodeWithTag("chatSteerSendAsNewMessage:1").performClick()
        assertEquals(
            "genau der zweite Original-Text wandert in den Composer",
            "Steuer-Zweites",
            draftText()
        )
        assertEquals("das erste Item bleibt erhalten", 1, recoveryItemCount())
        assertTrue("das erste Item behält seinen Platz/Text", recoveryItemTextPresent("Steuer-Erstes"))
        assertTrue(
            "der zweite Text darf nicht noch als Recovery-Item vorliegen",
            !recoveryItemTextPresent("Steuer-Zweites")
        )
        // No automatic sending: only the one original chat POST exists.
        assertEquals("nie automatisch gesendet", 1, source.calls.get())
    }

    @Test
    fun pendingSteerEqualToInFlightDraftYieldsExactlyOneRecoveryItem() {
        val steerer = FakeRunSteerRequester()
        val source = FakeTurnSource()
        steerer.gate = Channel(Channel.UNLIMITED)
        setScreen(history(emptyList()), source, runSteerRequester = steerer)

        waitForText(context.getString(R.string.chat_empty))
        composeRule.onNodeWithText(context.getString(R.string.chat_input_label)).performTextInput("Doppelt-Kein")
        ImeClickSettle.settleAfterTyping(composeRule)
        composeRule.onNodeWithText(context.getString(R.string.chat_send)).performClick()
        source.enqueue(ChatTurnEvent.RunStarted(RUN_ID))
        // Input with leading/trailing whitespace: the server returns the
        // normalized, trimmed wire text. The duplicate guard must compare the
        // exact, validated/sent text (trimmed), NOT the raw, untrimmed
        // `steerDraft` — otherwise `"  Gleich  "` != `"Gleich"` and a wrong
        // second item would arise.
        composeRule.onNodeWithTag("chatSteerInput").performTextInput("  Gleich  ")
        ImeClickSettle.settleAfterTyping(composeRule)
        composeRule.onNodeWithTag("chatSteerSend").performClick()
        assertTrue(steerer.started.await(3, TimeUnit.SECONDS))
        // Exactly the validated/sent (trimmed) wire text.
        assertEquals("Gleich", steerer.text.get())

        // The run ends while the steer request is still in flight, and its
        // `pending_steer` is the same request — here as the normalized wire
        // text `"Gleich"`. Exactly ONE recovery item, no duplicate capture of
        // the same request.
        source.enqueue(ChatTurnEvent.TurnCompleted(pendingSteer = "Gleich"))
        source.complete()
        waitForFlowCompletion(source)

        assertEquals("genau ein Item für denselben Request", 1, recoveryItemCount())
        assertTrue(
            "der normalisierte Wire-Text ist erhalten",
            recoveryItemTextPresent("Gleich")
        )
        // Explicit action: the one item moves into the composer, without
        // sending — as the normalized wire text.
        composeRule.onNodeWithTag("chatSteerSendAsNewMessage:0").performClick()
        assertEquals("Gleich", draftText())
        assertEquals(0, recoveryItemCount())
        assertEquals("nie automatisch gesendet", 1, source.calls.get())
    }

    @Test
    fun sessionSwitchClearsPreservedSteerRecoveryOfThePreviousSession() {
        val firstSteer = FakeRunSteerRequester()
        val secondSteer = FakeRunSteerRequester()
        val firstSource = FakeTurnSource()
        val secondSource = FakeTurnSource()
        firstSteer.release(RunSteerResult.NotAccepting(RUN_ID))
        val key = androidx.compose.runtime.mutableStateOf("first-session")
        composeRule.setContent {
            HermesTheme {
                ChatScreen(
                    historySource = history(emptyList()),
                    runController = controllerFor(if (key.value == "first-session") firstSource else secondSource),
                    runStopRequester = FakeRunStopRequester(),
                    runSteerRequester =
                        if (key.value == "first-session") firstSteer else secondSteer,
                    chatDraftRepository = NoOpChatDraftRepository(),
                    profileId = "fixture-profile-id",
                    sessionId = key.value,
                    onBack = {}
                )
            }
        }

        waitForText(context.getString(R.string.chat_empty))
        composeRule.onNodeWithText(context.getString(R.string.chat_input_label)).performTextInput("Erste Session")
        ImeClickSettle.settleAfterTyping(composeRule)
        composeRule.onNodeWithText(context.getString(R.string.chat_send)).performClick()
        assertTrue(firstSource.opened.await(3, TimeUnit.SECONDS))
        firstSource.enqueue(ChatTurnEvent.RunStarted(RUN_ID))
        composeRule.onNodeWithTag("chatSteerInput").performTextInput("Steht in Session 1")
        ImeClickSettle.settleAfterTyping(composeRule)
        composeRule.onNodeWithTag("chatSteerSend").performClick()
        assertTrue(firstSteer.started.await(3, TimeUnit.SECONDS))
        composeRule.waitUntil(timeoutMillis = 5_000) {
            try {
                composeRule.onNodeWithTag("chatSteerSendAsNewMessage:0").assertIsDisplayed()
                true
            } catch (_: AssertionError) {
                false
            }
        }
        firstSource.enqueue(ChatTurnEvent.TurnCompleted())
        firstSource.complete()
        waitForFlowCompletion(firstSource)

        // Session switch: the recovery state set of the old session
        // (profile/session key) must disappear completely.
        key.value = "second-session"
        composeRule.waitForIdle()
        waitForText(context.getString(R.string.chat_empty))
        assertTrue(
            "recovery of the previous session must be gone",
            composeRule.onAllNodesWithTag("chatSteerSendAsNewMessage:0")
                .fetchSemanticsNodes().isEmpty()
        )
        assertTrue(
            "steer input of the previous session must be gone",
            composeRule.onAllNodesWithTag("chatSteerInput")
                .fetchSemanticsNodes().isEmpty()
        )
        assertEquals("no steer request for the second session", 0, secondSteer.calls.get())
    }

    @Test
    fun steerRecoveryCardSitsAboveTheStopAndSteerControls() {
        val steerer = FakeRunSteerRequester()
        val source = FakeTurnSource()
        steerer.release(RunSteerResult.NotAccepting(RUN_ID))
        setScreen(history(emptyList()), source, runSteerRequester = steerer)

        waitForText(context.getString(R.string.chat_empty))
        composeRule.onNodeWithText(context.getString(R.string.chat_input_label)).performTextInput("Oben")
        ImeClickSettle.settleAfterTyping(composeRule)
        composeRule.onNodeWithText(context.getString(R.string.chat_send)).performClick()
        source.enqueue(ChatTurnEvent.RunStarted(RUN_ID))
        composeRule.onNodeWithTag("chatSteerInput").performTextInput("Nach oben")
        ImeClickSettle.settleAfterTyping(composeRule)
        composeRule.onNodeWithTag("chatSteerSend").performClick()
        assertTrue(steerer.started.await(3, TimeUnit.SECONDS))
        composeRule.waitUntil(timeoutMillis = 5_000) {
            try {
                composeRule.onNodeWithTag("chatSteerRecovery:0").assertIsDisplayed()
                true
            } catch (_: AssertionError) {
                false
            }
        }

        // #32: the "not taken over" note belongs above the course-correction
        // controls, not between the steer field and the message field. The run
        // is still active here, so stop and steer controls are bound too.
        val recoveryTop = composeRule.onNodeWithTag("chatSteerRecovery:0")
            .assertIsDisplayed()
            .getBoundsInRoot().top
        val steerTop = composeRule.onNodeWithTag("chatSteerInput")
            .assertIsDisplayed()
            .getBoundsInRoot().top
        val stopTop = composeRule.onNodeWithTag(TAG_RUN_STOP)
            .assertIsDisplayed()
            .getBoundsInRoot().top

        assertTrue(
            "recovery card top ($recoveryTop) must be above the steer input top ($steerTop)",
            recoveryTop < steerTop
        )
        assertTrue(
            "recovery card top ($recoveryTop) must be above the stop button top ($stopTop)",
            recoveryTop < stopTop
        )
    }

    /**
     * Number of recoverable steer items currently rendered.
     * Each item is its own ordered block tagged `chatSteerRecovery:<index>`
     * (contiguous from 0), so the count is the highest existing index + 1.
     * Uses only the public assertion APIs.
     */
    private fun recoveryItemCount(): Int {
        var count = 0
        while (
            composeRule
                .onAllNodesWithTag("chatSteerRecovery:$count")
                .fetchSemanticsNodes()
                .isNotEmpty()
        ) {
            count++
        }
        return count
    }

    /**
     * True while the exact item [text] is rendered as a recovery item. Scoped to the item text nodes tagged
     * `chatSteerRecoveryText`, so the same string sitting in the ordinary
     * composer (e.g. right after an explicit move) can never produce a
     * false positive. Used to assert that a preserved recovery item is
     * still present after a run boundary.
     */
    private fun recoveryItemTextPresent(text: String): Boolean {
        // `SemanticsProperties.Text` holds a List<AnnotatedString>; read the
        // actual annotated text of each item node rather than toString() on
        // the list. The card renders the preserved item in typographic quotes.
        val expected = context.getString(R.string.chat_steer_recovery_quote, text)
        return composeRule
            .onAllNodesWithTag("chatSteerRecoveryText")
            .fetchSemanticsNodes()
            .any { node ->
                node.config[SemanticsProperties.Text]
                    ?.any { it.text == expected }
                    ?: false
            }
    }

    private fun steerDraftText(): String? {
        return composeRule
            .onNodeWithTag("chatSteerInput")
            .fetchSemanticsNode()
            .config[SemanticsProperties.EditableText]
            ?.text
            ?.toString()
    }

    /**
     * Synthetic run-status fake: no network, no credentials. Records the
     * requested run ID and returns the pre-set [result] immediately (a status
     * read is a fast, idempotent GET).
     */
    private class FakeRunStatusRequester : RunStatusRequester {
        val calls = AtomicInteger()
        val runId = AtomicReference<String>()
        @Volatile
        var result: RunStatusResult = RunStatusResult.Known(RunServerStatus.RUNNING)

        override suspend fun getRunStatus(profileId: String, runId: String): RunStatusResult {
            calls.incrementAndGet()
            this.runId.set(runId)
            return result
        }
    }

    /**
     * Synthetic stop fake: no network, no credentials.
     * [gate] (when set) suspends the request until the test sends the
     * result; [release] returns a pre-set result immediately.
     */
    private class FakeRunStopRequester : RunStopRequester {
        val calls = AtomicInteger()
        val profileId = AtomicReference<String>()
        val runId = AtomicReference<String>()
        val started = CountDownLatch(1)
        // Counts down exactly once if the stop coroutine is canceled while
        // suspended on the gate (back / disposal). Never fires when a
        // result is delivered normally.
        val canceled = CountDownLatch(1)
        @Volatile
        var gate: Channel<RunStopResult>? = null
        @Volatile
        var immediateResult: RunStopResult = RunStopResult.Accepted("run_synthetic")

        override suspend fun stopRun(profileId: String, runId: String): RunStopResult {
            calls.incrementAndGet()
            this.profileId.set(profileId)
            this.runId.set(runId)
            started.countDown()
            val gate = this.gate
            return try {
                if (gate == null) immediateResult else gate.receive()
            } catch (error: CancellationException) {
                canceled.countDown()
                throw error
            }
        }

        fun release(result: RunStopResult) {
            immediateResult = result
        }
    }

    /**
     * Synthetic steer fake: no network, no credentials.
     * [gate] (when set) suspends the request until the test sends the
     * result; [release] returns a pre-set result immediately. The
     * request parameters are recorded BEFORE [started] counts down, so
     * a test that awaits [started] can inspect them race-free.
     */
    private class FakeRunSteerRequester : RunSteerRequester {
        val calls = AtomicInteger()
        val profileId = AtomicReference<String>()
        val runId = AtomicReference<String>()
        val text = AtomicReference<String>()
        val started = CountDownLatch(1)
        // Counts down exactly once if the steer coroutine is canceled
        // while suspended on the gate (back / disposal). Never fires
        // when a result is delivered normally.
        val canceled = CountDownLatch(1)
        @Volatile
        var gate: Channel<RunSteerResult>? = null
        @Volatile
        var immediateResult: RunSteerResult = RunSteerResult.Queued("run_synthetic")

        override suspend fun steerRun(
            profileId: String,
            runId: String,
            text: String
        ): RunSteerResult {
            calls.incrementAndGet()
            this.profileId.set(profileId)
            this.runId.set(runId)
            this.text.set(text)
            started.countDown()
            val gate = this.gate
            return try {
                if (gate == null) immediateResult else gate.receive()
            } catch (error: CancellationException) {
                canceled.countDown()
                throw error
            }
        }

        fun release(result: RunSteerResult) {
            immediateResult = result
        }
    }
}
