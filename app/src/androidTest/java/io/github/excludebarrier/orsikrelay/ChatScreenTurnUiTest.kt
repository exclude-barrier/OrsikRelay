package io.github.excludebarrier.orsikrelay

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import io.github.excludebarrier.orsikrelay.data.chat.ChatDraftRepository
import io.github.excludebarrier.orsikrelay.domain.chat.ChatStreamError
import io.github.excludebarrier.orsikrelay.domain.chat.ChatTurnEvent
import io.github.excludebarrier.orsikrelay.domain.chat.StreamingChatSource
import io.github.excludebarrier.orsikrelay.domain.message.HermesChatMessage
import io.github.excludebarrier.orsikrelay.domain.message.MessageHistoryResult
import io.github.excludebarrier.orsikrelay.domain.message.MessageHistorySource
import io.github.excludebarrier.orsikrelay.domain.message.MessagePage
import io.github.excludebarrier.orsikrelay.domain.message.MessageRole
import io.github.excludebarrier.orsikrelay.domain.run.RunStopError
import io.github.excludebarrier.orsikrelay.domain.run.RunStopRequester
import io.github.excludebarrier.orsikrelay.domain.run.RunStopResult
import io.github.excludebarrier.orsikrelay.domain.run.RunSteerError
import io.github.excludebarrier.orsikrelay.domain.run.RunSteerRequester
import io.github.excludebarrier.orsikrelay.domain.run.RunSteerResult
import io.github.excludebarrier.orsikrelay.draft.NoOpChatDraftRepository
import io.github.excludebarrier.orsikrelay.ui.chat.ChatScreen
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Synthetic Compose tests for the live turn slice of the chat screen
 * (Mission 006A, Batch 2). The streaming seam is a fake: no network, no
 * real hosts, no credentials.
 */
class ChatScreenTurnUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun composerAppearsAfterHistoryLoadAndBlankInputIsDisabled() {
        val source = FakeTurnSource()
        setScreen(history(emptyList()), source)

        waitForText("Diese Session enthält noch keine Nachrichten.")
        composeRule.onNodeWithText("Nachricht").assertIsDisplayed()
        composeRule.onNodeWithText("Senden").assertIsDisplayed()
        // Leerer Entwurf: Senden ist deaktiviert und ein Tipp sendet nichts.
        composeRule.onNodeWithText("Senden").assertIsNotEnabled()
        composeRule.onNodeWithText("Senden").performClick()
        assertEquals(0, source.calls.get())

        // Whitespace-Entwurf: ebenfalls deaktiviert.
        composeRule.onNodeWithText("Nachricht").performTextInput(" \n \t ")
        composeRule.onNodeWithText("Senden").assertIsNotEnabled()
        assertEquals(" \n \t ", draftText())
        assertEquals(0, source.calls.get())
    }

    @Test
    fun doubleTapStartsExactlyOneStreamAndKeepsDraftUntilRunStarted() {
        val source = FakeTurnSource()
        setScreen(history(emptyList()), source)

        waitForText("Diese Session enthält noch keine Nachrichten.")
        composeRule
            .onNodeWithText("Nachricht")
            .performTextInput("  Doppelte Eingabe  ")
        composeRule.onNodeWithText("Senden").performClick()
        assertTrue("first stream was not started", source.opened.await(3, TimeUnit.SECONDS))
        // Zweiter, schneller Tipp während des laufenden Turns.
        composeRule.onNodeWithText("Senden").performClick()
        assertEquals("exactly one stream must run", 1, source.calls.get())
        // Lokale Userzeile erscheint sofort nach akzeptiertem Submit.
        waitForText("Doppelte Eingabe")
        // Vor RunStarted bleibt der Entwurf im Feld erhalten.
        assertEquals("  Doppelte Eingabe  ", draftText())

        source.enqueue(ChatTurnEvent.RunStarted("run_synthetic"))
        // Erst RunStarted leert den Entwurf.
        composeRule.waitUntil(timeoutMillis = 5_000) {
            draftText().isNullOrBlank()
        }
        composeRule.waitForIdle()
        assertEquals(1, source.calls.get())

        source.enqueue(ChatTurnEvent.TurnCompleted())
        source.complete()
        waitForFlowCompletion(source)
        // TurnCompleted gibt den Composer frei: das Feld ist wieder
        // editierbar. Bei leerem Entwurf ist "Senden" sichtbar, aber
        // deaktiviert.
        composeRule.onNodeWithText("Nachricht").assertIsEnabled()
        composeRule.onNodeWithText("Senden").assertIsDisplayed()
        composeRule.onNodeWithText("Senden").assertIsNotEnabled()
    }

    @Test
    fun deltasGrowOneLineAndFinalTextReplacesIt() {
        val source = FakeTurnSource()
        setScreen(history(emptyList()), source)

        waitForText("Diese Session enthält noch keine Nachrichten.")
        composeRule.onNodeWithText("Nachricht").performTextInput("Hallo")
        composeRule.onNodeWithText("Senden").performClick()

        // Beide Deltas wachsen sichtbar in derselben Zeile. Der Stream
        // wird erst nach der Beobachtung des Zwischenzustands
        // fortgesetzt, weil das finale Ereignis die zusammengesetzte
        // Zeile ersetzt.
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
        // Der finale Text ersetzt die zusammengesetzte Zeile.
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
        // TurnCompleted gibt den Composer frei: das Feld ist wieder
        // editierbar. Bei leerem Entwurf ist "Senden" sichtbar, aber
        // deaktiviert.
        composeRule.onNodeWithText("Nachricht").assertIsEnabled()
        composeRule.onNodeWithText("Senden").assertIsDisplayed()
        composeRule.onNodeWithText("Senden").assertIsNotEnabled()
        waitForFlowCompletion(source)
    }

    @Test
    fun toolActivityIsGenericWithoutNameArgsOrReasoning() {
        val source = FakeTurnSource()
        setScreen(history(emptyList()), source)

        waitForText("Diese Session enthält noch keine Nachrichten.")
        composeRule
            .onNodeWithText("Nachricht")
            .performTextInput("Werkzeugtest")
        composeRule.onNodeWithText("Senden").performClick()

        source.enqueue(
            ChatTurnEvent.RunStarted("run_synthetic"),
            ChatTurnEvent.AssistantStarted,
            ChatTurnEvent.ToolActivity
        )
        // Nur der generische Status ist sichtbar. Der Stream wird erst
        // nach der Beobachtung fortgesetzt, weil die finale Zeile die
        // zusammengesetzten Inhalte ersetzt.
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Tool ist aktiv…")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Tool ist aktiv…").assertIsDisplayed()
        // Synthetische Toolname/Args/Preview/Reasoning erscheinen nirgends.
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
        // Bei leerem Entwurf ist "Senden" sichtbar, aber deaktiviert.
        composeRule.onNodeWithText("Senden").assertIsDisplayed()
        composeRule.onNodeWithText("Senden").assertIsNotEnabled()
    }

    @Test
    fun failureBeforeStartKeepsDraftAndSendsNoRefreshButton() {
        val source = FakeTurnSource()
        setScreen(history(emptyList()), source)

        waitForText("Diese Session enthält noch keine Nachrichten.")
        composeRule
            .onNodeWithText("Nachricht")
            .performTextInput("Bleibt im Entwurf")
        composeRule.onNodeWithText("Senden").performClick()

        source.enqueue(ChatTurnEvent.TurnFailed(ChatStreamError.Network))
        source.complete()
        waitForFlowCompletion(source)

        // Generische Meldung, Composer wieder frei, Entwurf erhalten.
        composeRule
            .onNodeWithText("Der Hermes-Server ist derzeit nicht erreichbar.")
            .assertIsDisplayed()
        assertEquals("Bleibt im Entwurf", draftText())
        composeRule.onNodeWithText("Senden").assertIsEnabled()
        composeRule.onNodeWithText("Nachricht").assertIsEnabled()
        // Vor Start gibt es keinen History-Refresh-Button.
        assertTrue(
            "no refresh button before run start",
            composeRule.onAllNodesWithText("Verlauf aktualisieren")
                .fetchSemanticsNodes().isEmpty()
        )
        assertEquals(1, source.calls.get())
    }

    @Test
    fun failureAfterStartOffersHistoryRefreshWithoutResending() {
        val historyCalls = AtomicInteger()
        val source = FakeTurnSource()
        setScreen(
            MessageHistorySource { _, _ ->
                historyCalls.incrementAndGet()
                MessageHistoryResult.Success(MessagePage(emptyList()))
            },
            source
        )

        waitForText("Diese Session enthält noch keine Nachrichten.")
        composeRule
            .onNodeWithText("Nachricht")
            .performTextInput("Nachrichten")
        composeRule.onNodeWithText("Senden").performClick()

        source.enqueue(
            ChatTurnEvent.RunStarted("run_synthetic"),
            ChatTurnEvent.TurnFailed(ChatStreamError.Network)
        )
        source.complete()
        waitForFlowCompletion(source)

        composeRule
            .onNodeWithText("Der Hermes-Server ist derzeit nicht erreichbar.")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Verlauf aktualisieren").assertIsDisplayed()
        assertEquals(1, historyCalls.get())

        composeRule.onNodeWithText("Verlauf aktualisieren").performClick()
        // Der bestehende idempotente History-Read wird erneut ausgeführt;
        // der Read läuft asynchron auf IO, daher wird auf den zweiten
        // History-Call gewartet, statt sofort gegen den Zähler zu
        // asserten. Der POST wird niemals erneut gesendet.
        composeRule.waitUntil(timeoutMillis = 5_000) { historyCalls.get() == 2 }
        composeRule.waitForIdle()
        assertEquals(2, historyCalls.get())
        assertEquals("no automatic resending", 1, source.calls.get())
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

        composeRule.onNodeWithText("Nachricht").performTextInput("Neue Live-Nachricht")
        composeRule.onNodeWithText("Senden").performClick()
        assertTrue(source.opened.await(3, TimeUnit.SECONDS))

        waitForText("Neue Live-Nachricht")
        composeRule
            .onNodeWithTag("chatTimelineRow:local:1")
            .assertIsDisplayed()
    }

    @Test
    fun backDuringRunningTurnCancelsTheStreamBeforeOnBack() {
        val source = FakeTurnSource()
        val backInvoked = CountDownLatch(1)
        val canceledBeforeBack = CountDownLatch(1)
        setScreen(
            history(emptyList()),
            source,
            onBack = {
                // Beobachtung: die Cancellation wurde bereits vor dem
                // Back-Callback registriert.
                if (source.canceled.getCount() == 0L) {
                    canceledBeforeBack.countDown()
                }
                backInvoked.countDown()
            }
        )

        waitForText("Diese Session enthält noch keine Nachrichten.")
        composeRule
            .onNodeWithText("Nachricht")
            .performTextInput("Läuft noch")
        composeRule.onNodeWithText("Senden").performClick()
        assertTrue(source.opened.await(3, TimeUnit.SECONDS))
        assertEquals(1, source.calls.get())

        composeRule.onNodeWithText("Zurück").performClick()
        assertTrue("onBack was not invoked", backInvoked.await(3, TimeUnit.SECONDS))
        assertTrue(
            "the running stream must be canceled before onBack",
            source.canceled.await(3, TimeUnit.SECONDS)
        )
        assertTrue(
            "cancellation must have been observed before the back callback",
            canceledBeforeBack.await(3, TimeUnit.SECONDS)
        )
        assertEquals(1, source.calls.get())
    }

    @Test
    fun normalFlowEndWithoutTerminalEventIsAGenericFailureAndKeepsComposerBlocked() {
        val source = FakeTurnSource()
        setScreen(history(emptyList()), source)

        waitForText("Diese Session enthält noch keine Nachrichten.")
        composeRule
            .onNodeWithText("Nachricht")
            .performTextInput("Plötzliches Ende")
        composeRule.onNodeWithText("Senden").performClick()

        source.enqueue(
            ChatTurnEvent.RunStarted("run_synthetic"),
            ChatTurnEvent.AssistantStarted
        )
        // Der Stream endet normal, ohne `TurnCompleted` und ohne
        // `TurnFailed`.
        source.complete()
        waitForFlowCompletion(source)

        // Kein stiller Erfolg: generische Fehlermeldung, Composer bleibt
        // blockiert (kein Submit möglich), History-Refresh ist vorhanden,
        // kein Auto-Retry.
        composeRule
            .onNodeWithText("Der Hermes-Server ist derzeit nicht erreichbar.")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Verlauf aktualisieren").assertIsDisplayed()
        composeRule.onNodeWithText("Senden").assertIsDisplayed()
        composeRule.onNodeWithText("Senden").assertIsNotEnabled()
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

        waitForText("Diese Session enthält noch keine Nachrichten.")
        composeRule.onNodeWithText("Nachricht").performTextInput("Sofortig")
        composeRule.onNodeWithText("Senden").performClick()

        // The final text is rendered and the turn is released: the
        // field is editable again and the (empty) draft keeps "Senden"
        // disabled. With the bug present `turnActive` would never clear
        // and the field would stay disabled forever.
        waitForText("Sofort fertig")
        composeRule.onNodeWithText("Nachricht").assertIsEnabled()
        composeRule.onNodeWithText("Senden").assertIsDisplayed()
        composeRule.onNodeWithText("Senden").assertIsNotEnabled()
    }

    @Test
    fun completedEventReleasesComposerWithoutSourceCompletion() {
        val source = FakeTurnSource()
        setScreen(history(emptyList()), source)

        waitForText("Diese Session enthält noch keine Nachrichten.")
        composeRule.onNodeWithText("Nachricht").performTextInput("Abschluss")
        composeRule.onNodeWithText("Senden").performClick()
        assertTrue(source.opened.await(3, TimeUnit.SECONDS))

        source.enqueue(ChatTurnEvent.RunStarted("run_synthetic"), ChatTurnEvent.TurnCompleted())

        composeRule.waitUntil(timeoutMillis = 5_000) {
            try {
                composeRule.onNodeWithText("Nachricht").assertIsEnabled()
                true
            } catch (_: AssertionError) {
                false
            }
        }
        assertEquals("collector must stop at TurnCompleted", 0L, source.canceled.count)
    }

    @Test
    fun failedEventReleasesComposerWithoutSourceCompletion() {
        val source = FakeTurnSource()
        setScreen(history(emptyList()), source)

        waitForText("Diese Session enthält noch keine Nachrichten.")
        composeRule.onNodeWithText("Nachricht").performTextInput("Fehler")
        composeRule.onNodeWithText("Senden").performClick()
        assertTrue(source.opened.await(3, TimeUnit.SECONDS))

        source.enqueue(
            ChatTurnEvent.RunStarted("run_synthetic"),
            ChatTurnEvent.TurnFailed(ChatStreamError.Network)
        )

        composeRule.waitUntil(timeoutMillis = 5_000) {
            try {
                composeRule.onNodeWithText("Nachricht").assertIsEnabled()
                true
            } catch (_: AssertionError) {
                false
            }
        }
        assertEquals("collector must stop at TurnFailed", 0L, source.canceled.count)
    }

    @Test
    fun sessionSwitchCancelsRunningCollectorAndResetsEphemeralState() {
        val firstSource = FakeTurnSource()
        val secondSource = FakeTurnSource()
        val key = androidx.compose.runtime.mutableStateOf("first-session")
        composeRule.setContent {
            HermesTheme {
                ChatScreen(
                    historySource = history(emptyList()),
                    chatSource = if (key.value == "first-session") firstSource else secondSource,
                    runStopRequester = FakeRunStopRequester(),
                    runSteerRequester = FakeRunSteerRequester(),
                    chatDraftRepository = NoOpChatDraftRepository(),
                    profileId = "fixture-profile-id",
                    sessionId = key.value,
                    onBack = {}
                )
            }
        }

        waitForText("Diese Session enthält noch keine Nachrichten.")
        composeRule
            .onNodeWithText("Nachricht")
            .performTextInput("Läuft in erster Session")
        composeRule.onNodeWithText("Senden").performClick()
        assertTrue(firstSource.opened.await(3, TimeUnit.SECONDS))
        waitForText("Du")

        // Sessionwechsel bricht den laufenden Collector ab und setzt alle
        // ephemeren Turnzustände zurück.
        key.value = "second-session"
        composeRule.waitForIdle()

        assertTrue(
            "the running collector must be canceled on session switch",
            firstSource.canceled.await(3, TimeUnit.SECONDS)
        )
        assertEquals(1, firstSource.calls.get())
        assertEquals(0, secondSource.calls.get())
        assertTrue(
            "ephemeral turn state must be reset",
            composeRule.onAllNodesWithText("Läuft in erster Session")
                .fetchSemanticsNodes().isEmpty()
        )
        // History der neuen Session wird geladen, Composer ist frei.
        waitForText("Diese Session enthält noch keine Nachrichten.")
        composeRule.onNodeWithText("Nachricht").assertIsEnabled()
    }

    private fun setScreen(
        source: MessageHistorySource,
        chatSource: StreamingChatSource,
        onBack: () -> Unit = {},
        runStopRequester: RunStopRequester = FakeRunStopRequester(),
        runSteerRequester: RunSteerRequester = FakeRunSteerRequester(),
        chatDraftRepository: ChatDraftRepository = NoOpChatDraftRepository()
    ) {
        composeRule.setContent {
            HermesTheme {
                ChatScreen(
                    historySource = source,
                    chatSource = chatSource,
                    runStopRequester = runStopRequester,
                    runSteerRequester = runSteerRequester,
                    chatDraftRepository = chatDraftRepository,
                    profileId = "fixture-profile-id",
                    sessionId = "fixture-session-id",
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
        val nodes = composeRule.onAllNodesWithText("Nachricht").fetchSemanticsNodes()
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
    // Mission 007B: the run-stop slice of the chat screen. All IDs are
    // synthetic; no network, no credentials, no real server.
    // ------------------------------------------------------------------

    @Test
    fun stopControlIsInvisibleBeforeRunStartedAndGoneAfterTerminalEvent() {
        val stopper = FakeRunStopRequester()
        val source = FakeTurnSource()
        setScreen(history(emptyList()), source, runStopRequester = stopper)

        waitForText("Diese Session enthält noch keine Nachrichten.")
        composeRule.onNodeWithText("Nachricht").performTextInput("Stop-Test")
        composeRule.onNodeWithText("Senden").performClick()
        assertTrue(source.opened.await(3, TimeUnit.SECONDS))

        // Vor run.started ist der Stop-Control unsichtbar; ein Tipp
        // kann den Stop-POST nicht erreichen.
        assertTrue(
            "stop control must be invisible before RunStarted",
            composeRule.onAllNodesWithText("Stoppen").fetchSemanticsNodes().isEmpty()
        )
        assertEquals(0, stopper.calls.get())

        source.enqueue(ChatTurnEvent.RunStarted(RUN_ID))
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Stoppen").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Stoppen").assertIsEnabled()

        // Der terminale Streamzustand beendet den Turn; danach ist der
        // Stop-Control wieder unsichtbar.
        source.enqueue(ChatTurnEvent.TurnCompleted())
        source.complete()
        waitForFlowCompletion(source)
        assertTrue(
            "stop control must be invisible after the terminal event",
            composeRule.onAllNodesWithText("Stoppen").fetchSemanticsNodes().isEmpty()
        )
    }

    @Test
    fun doubleTapOnStopSendsExactlyOneRequestAndShowsStopping() {
        val stopper = FakeRunStopRequester()
        val source = FakeTurnSource()
        setScreen(history(emptyList()), source, runStopRequester = stopper)

        waitForText("Diese Session enthält noch keine Nachrichten.")
        composeRule.onNodeWithText("Nachricht").performTextInput("Stop-Doppeltipp")
        composeRule.onNodeWithText("Senden").performClick()
        source.enqueue(ChatTurnEvent.RunStarted(RUN_ID))
        waitForText("Stoppen")

        composeRule.onNodeWithText("Stoppen").performClick()
        assertTrue("stop request must start", stopper.started.await(3, TimeUnit.SECONDS))
        // Schneller zweiter Tipp während der laufenden Anfrage: der
        // Control ist deaktiviert und es gibt genau einen Aufruf.
        composeRule
            .onNodeWithText("Wird gestoppt …")
            .assertIsDisplayed()
            .assertIsNotEnabled()
        composeRule.onNodeWithText("Wird gestoppt …").performClick()
        assertEquals("exactly one stop request", 1, stopper.calls.get())
        assertEquals(RUN_ID, stopper.runId.get())
        assertEquals("fixture-profile-id", stopper.profileId.get())

        // Der Stream läuft weiter (kein lokaler Abbruch): der Abbruch-Latch
        // ist noch intakt (1); erst das terminale Event beendet den Collector.
        assertEquals("stream not interrupted by the stop", 1L, source.canceled.count)
        stopper.release(RunStopResult.Accepted(RUN_ID))
        source.enqueue(ChatTurnEvent.TurnCompleted())
        source.complete()
        waitForFlowCompletion(source)
        assertTrue(
            "stop control must be gone after the terminal event",
            composeRule.onAllNodesWithText("Wird gestoppt …").fetchSemanticsNodes().isEmpty()
        )
        assertEquals(1, stopper.calls.get())
    }

    @Test
    fun acceptedKeepsStoppingLabelUntilTheTerminalStreamEvent() {
        val stopper = FakeRunStopRequester()
        val source = FakeTurnSource()
        setScreen(history(emptyList()), source, runStopRequester = stopper)

        waitForText("Diese Session enthält noch keine Nachrichten.")
        composeRule.onNodeWithText("Nachricht").performTextInput("Stop-Accepted")
        composeRule.onNodeWithText("Senden").performClick()
        source.enqueue(ChatTurnEvent.RunStarted(RUN_ID))
        waitForText("Stoppen")

        stopper.gate = Channel(Channel.UNLIMITED)
        composeRule.onNodeWithText("Stoppen").performClick()
        assertTrue(stopper.started.await(3, TimeUnit.SECONDS))
        composeRule.waitUntil(timeoutMillis = 5_000) {
            try {
                composeRule.onNodeWithText("Wird gestoppt …").assertIsDisplayed()
                true
            } catch (_: AssertionError) {
                false
            }
        }

        // Accepted: nur "Stop angenommen" — kein optimistischer
        // Terminalzustand, Composer bleibt blockiert, der Stream wird
        // nicht lokal abgebrochen.
        stopper.gate!!.trySend(RunStopResult.Accepted(RUN_ID))
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Wird gestoppt …").assertIsDisplayed()
        composeRule.onNodeWithText("Nachricht").assertIsNotEnabled()
        composeRule.onNodeWithText("Senden").assertIsNotEnabled()
        // Der Collector läuft unverändert weiter (Abbruch-Latch intakt = 1);
        // erst das terminale Stream-Event beendet ihn.
        assertEquals("stream must not be interrupted locally", 1L, source.canceled.count)
        assertEquals(1, source.calls.get())
        assertEquals(1, stopper.calls.get())

        // Erst der vorhandene terminale Streamzustand beendet den Turn
        // und entfernt den Stop-Control.
        source.enqueue(ChatTurnEvent.TurnCompleted())
        source.complete()
        waitForFlowCompletion(source)
        composeRule.onNodeWithText("Nachricht").assertIsEnabled()
        assertTrue(
            "stop control must be gone after the terminal event",
            composeRule.onAllNodesWithText("Wird gestoppt …").fetchSemanticsNodes().isEmpty()
        )
        // Die Run-ID erscheint nie in der UI.
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

        waitForText("Diese Session enthält noch keine Nachrichten.")
        composeRule.onNodeWithText("Nachricht").performTextInput("Stop-Fehler")
        composeRule.onNodeWithText("Senden").performClick()
        source.enqueue(ChatTurnEvent.RunStarted(RUN_ID))
        waitForText("Stoppen")

        stopper.gate = Channel(Channel.UNLIMITED)
        composeRule.onNodeWithText("Stoppen").performClick()
        assertTrue(stopper.started.await(3, TimeUnit.SECONDS))
        // Generische lokale Meldung ohne dynamische Details; die
        // laufende Streamsammlung bleibt unverändert; ein manueller
        // erneuter Versuch ist wieder möglich.
        stopper.gate!!.trySend(RunStopResult.Failure(RunStopError.Network))
        composeRule.waitUntil(timeoutMillis = 5_000) {
            try {
                composeRule
                    .onNodeWithText("Der laufende Run konnte nicht gestoppt werden.")
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
        composeRule.onNodeWithText("Stoppen").assertIsEnabled()

        // Der bewusste manuelle Retry sendet genau einen weiteren
        // Request.
        stopper.gate = Channel(Channel.UNLIMITED)
        composeRule.onNodeWithText("Stoppen").performClick()
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

        waitForText("Diese Session enthält noch keine Nachrichten.")
        composeRule.onNodeWithText("Nachricht").performTextInput("Stop-AlreadyTerminal")
        composeRule.onNodeWithText("Senden").performClick()
        source.enqueue(ChatTurnEvent.RunStarted(RUN_ID))
        waitForText("Stoppen")
        assertEquals(1, historyCalls.get())

        stopper.gate = Channel(Channel.UNLIMITED)
        composeRule.onNodeWithText("Stoppen").performClick()
        assertTrue(stopper.started.await(3, TimeUnit.SECONDS))
        stopper.gate!!.trySend(RunStopResult.AlreadyTerminal(RUN_ID))

        // Der bestehende History-Refresh/Reconciliation-Pfad wird
        // ausgelöst: genau ein zusätzlicher idempotenter History-Read;
        // der Chat-POST wird niemals erneut gesendet.
        composeRule.waitUntil(timeoutMillis = 5_000) { historyCalls.get() == 2 }
        assertEquals(2, historyCalls.get())
        assertEquals("no chat resending", 1, source.calls.get())
        assertEquals("no second stop request", 1, stopper.calls.get())
        composeRule.waitForIdle()
        // Kein Stop-Control mehr; der Composer ist für einen neuen Run
        // frei.
        assertTrue(
            "stop control must be gone after reconciliation",
            composeRule.onAllNodesWithText("Stoppen").fetchSemanticsNodes().isEmpty()
        )
        composeRule.onNodeWithText("Nachricht").assertIsEnabled()
    }

    @Test
    fun newRunResetsStopStateAndBindsOnlyItsOwnRunId() {
        val stopper = FakeRunStopRequester()
        val source = FakeTurnSource()
        setScreen(history(emptyList()), source, runStopRequester = stopper)

        waitForText("Diese Session enthält noch keine Nachrichten.")
        // Erster Run: Stop schlägt fehl (generisch). Der Fake hat hier
        // kein Gate, daher wird das Ergebnis vor dem Tipp synchron
        // vorbereitet, damit der (ungegatede) Aufruf es zurückgibt.
        composeRule.onNodeWithText("Nachricht").performTextInput("Erster Lauf")
        composeRule.onNodeWithText("Senden").performClick()
        source.enqueue(ChatTurnEvent.RunStarted(RUN_ID))
        waitForText("Stoppen")
        stopper.release(RunStopResult.Failure(RunStopError.Network))
        composeRule.onNodeWithText("Stoppen").performClick()
        assertTrue(stopper.started.await(3, TimeUnit.SECONDS))
        composeRule.waitUntil(timeoutMillis = 5_000) {
            try {
                composeRule
                    .onNodeWithText("Der laufende Run konnte nicht gestoppt werden.")
                    .assertIsDisplayed()
                true
            } catch (_: AssertionError) {
                false
            }
        }
        assertEquals(1, stopper.calls.get())

        // Sauberer terminaler Abschluss des ersten Runs: Stop-Zustand
        // (incl. Failure) und gebundene Run-ID werden zurückgesetzt und
        // der Composer wird frei.
        source.enqueue(ChatTurnEvent.TurnCompleted())
        source.complete()
        waitForFlowCompletion(source)
        composeRule.onNodeWithText("Nachricht").assertIsEnabled()

        // Zweiter Run: der Stop-Zustand des ersten Runs ist weg und es
        // wird nur die Run-ID des neuen Runs gebunden.
        stopper.release(RunStopResult.Accepted(NEW_RUN_ID))
        composeRule.onNodeWithText("Nachricht").performTextInput("Zweiter Lauf")
        composeRule.onNodeWithText("Senden").performClick()
        assertEquals(2, source.calls.get())
        source.enqueue(ChatTurnEvent.RunStarted(NEW_RUN_ID))
        waitForText("Stoppen")
        composeRule.onNodeWithText("Stoppen").performClick()
        assertTrue(stopper.started.await(3, TimeUnit.SECONDS))
        assertEquals("new run binds only its own run id", NEW_RUN_ID, stopper.runId.get())
        assertEquals("second stop is exactly one further call", 2, stopper.calls.get())
        // Die alte Run-ID darf nirgends (mehr) auftauchen.
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
    fun backWhileStopRequestIsInFlightCancelsItSafelyBeforeOnBack() {
        val stopper = FakeRunStopRequester()
        val source = FakeTurnSource()
        val backInvoked = CountDownLatch(1)
        setScreen(
            history(emptyList()),
            source,
            onBack = { backInvoked.countDown() },
            runStopRequester = stopper
        )

        waitForText("Diese Session enthält noch keine Nachrichten.")
        composeRule.onNodeWithText("Nachricht").performTextInput("Stop-Back")
        composeRule.onNodeWithText("Senden").performClick()
        source.enqueue(ChatTurnEvent.RunStarted(RUN_ID))
        waitForText("Stoppen")

        // Der Stop-Aufruf bleibt in der Anfrage hängen.
        stopper.gate = Channel(Channel.UNLIMITED)
        composeRule.onNodeWithText("Stoppen").performClick()
        assertTrue(stopper.started.await(3, TimeUnit.SECONDS))
        composeRule.waitUntil(timeoutMillis = 5_000) {
            try {
                composeRule.onNodeWithText("Wird gestoppt …").assertIsDisplayed()
                true
            } catch (_: AssertionError) {
                false
            }
        }

        // Back muss auch die laufende Stop-Coroutine abbrechen und
        // sicher zurückkehren.
        composeRule.onNodeWithText("Zurück").performClick()
        assertTrue("onBack was not invoked", backInvoked.await(3, TimeUnit.SECONDS))
        assertTrue(
            "the in-flight stop coroutine must be canceled by back",
            stopper.canceled.await(3, TimeUnit.SECONDS)
        )
        assertEquals("no state write may follow after the screen was left", 1, stopper.calls.get())
        assertEquals(1, source.calls.get())
        assertTrue(
            "stop control must be gone after back",
            composeRule.onAllNodesWithText("Wird gestoppt …").fetchSemanticsNodes().isEmpty()
        )
        // Ein später Callback (Resultat nach dem Verlassen) darf den
        // verlassenen Screen nicht mehr mutieren: der Screen ist
        // zurückgesetzt, der Composer ist frei.
        stopper.gate!!.trySend(RunStopResult.Accepted(RUN_ID))
        composeRule.waitForIdle()
        assertTrue(
            "late result must not resurrect the stop control",
            composeRule.onAllNodesWithText("Wird gestoppt …").fetchSemanticsNodes().isEmpty()
        )
        composeRule.onNodeWithText("Nachricht").assertIsEnabled()
    }

    private companion object {
        const val RUN_ID = "run_synthetic"
        const val NEW_RUN_ID = "run_synthetic_2"
    }

    // ------------------------------------------------------------------
    // Mission 008B: the run-steer slice of the chat screen. All IDs are
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

        waitForText("Diese Session enthält noch keine Nachrichten.")
        composeRule.onNodeWithText("Nachricht").performTextInput("Steuer-Sichtbarkeit")
        composeRule.onNodeWithText("Senden").performClick()
        assertTrue(source.opened.await(3, TimeUnit.SECONDS))

        // Vor run.started ist die Steuer-Eingabe unsichtbar; ein Tipp
        // kann den Steuer-POST nicht erreichen.
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

        // Der terminale Streamzustand beendet den Turn; danach ist die
        // Steuer-Eingabe wieder unsichtbar.
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

        waitForText("Diese Session enthält noch keine Nachrichten.")
        composeRule.onNodeWithText("Nachricht").performTextInput("Steuer-Queued")
        composeRule.onNodeWithText("Senden").performClick()
        source.enqueue(ChatTurnEvent.RunStarted(RUN_ID))
        composeRule.onNodeWithTag("chatSteerInput").performTextInput("Kurs nach links")
        composeRule.onNodeWithTag("chatSteerSend").performClick()
        assertTrue("steer request must start", steerer.started.await(3, TimeUnit.SECONDS))
        // Gated: die Request-Parameter sind gesetzt, bevor das Ergebnis
        // ausgeliefert wird — kein Race beim Prüfen des exakten Textes.
        assertEquals(RUN_ID, steerer.runId.get())
        assertEquals("fixture-profile-id", steerer.profileId.get())
        // Exakter, getrimmter Nutzertext — keine andere Zeichenfolge.
        assertEquals("Kurs nach links", steerer.text.get())
        steerer.gate!!.trySend(RunSteerResult.Queued(RUN_ID))

        // Eingereiht: kurze Bestätigung, Steuer-Eingabe geleert, der Run
        // bleibt aktiv — kein lokaler Terminalzustand, keine neue
        // Chat-Nachricht.
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
        // Der Run ist nach wie vor aktiv: Composer und Senden sind blockiert.
        composeRule.onNodeWithText("Nachricht").assertIsNotEnabled()
        composeRule.onNodeWithText("Senden").assertIsNotEnabled()
        // Die Run-ID erscheint nie in der UI.
        assertTrue(
            "run id must not be rendered",
            composeRule.onAllNodesWithText(RUN_ID).fetchSemanticsNodes().isEmpty()
        )

        // Erst das terminale Stream-Event beendet den Turn.
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

        waitForText("Diese Session enthält noch keine Nachrichten.")
        composeRule.onNodeWithText("Nachricht").performTextInput("Steuer-409")
        composeRule.onNodeWithText("Senden").performClick()
        source.enqueue(ChatTurnEvent.RunStarted(RUN_ID))
        composeRule.onNodeWithTag("chatSteerInput").performTextInput("Kurs nach rechts")
        composeRule.onNodeWithTag("chatSteerSend").performClick()
        assertTrue(steerer.started.await(3, TimeUnit.SECONDS))
        assertEquals("Kurs nach rechts", steerer.text.get())
        steerer.gate!!.trySend(RunSteerResult.NotAccepting(RUN_ID))

        // 409: der exakte Text bleibt in der wiederherstellbaren
        // Zustandsmenge; die explizite Aktion erscheint.
        composeRule.waitUntil(timeoutMillis = 5_000) {
            try {
                composeRule.onNodeWithTag("chatSteerSendAsNewMessage:0").assertIsDisplayed()
                true
            } catch (_: AssertionError) {
                false
            }
        }
        assertTrue("steer input must be empty after 409", steerDraftText().isNullOrBlank())

        // Die explizite Aktion verschiebt den Text in den gewöhnlichen
        // Composer — ohne ihn zu senden.
        composeRule.onNodeWithTag("chatSteerSendAsNewMessage:0").performClick()
        assertEquals("Kurs nach rechts", draftText())
        assertTrue(
            "recovery block must be gone after the explicit action",
            composeRule.onAllNodesWithTag("chatSteerSendAsNewMessage:0")
                .fetchSemanticsNodes().isEmpty()
        )
        assertEquals("the text must never be auto-sent", 1, source.calls.get())

        // Der Run läuft noch: der Composer ist blockiert, der Text bleibt
        // aber erhalten. Erst nach dem terminalen Event ist er sendbar.
        composeRule.onNodeWithText("Nachricht").assertIsNotEnabled()
        source.enqueue(ChatTurnEvent.TurnCompleted())
        source.complete()
        waitForFlowCompletion(source)
        composeRule.onNodeWithText("Nachricht").assertIsEnabled()
        assertEquals("Kurs nach rechts", draftText())
        assertEquals("still not auto-sent after completion", 1, source.calls.get())
    }

    @Test
    fun pendingSteerFromRunCompletedIsRecoveredThroughTheSameActionWithoutAutoSend() {
        val source = FakeTurnSource()
        setScreen(history(emptyList()), source, runSteerRequester = FakeRunSteerRequester())

        waitForText("Diese Session enthält noch keine Nachrichten.")
        composeRule.onNodeWithText("Nachricht").performTextInput("Pending-Steer")
        composeRule.onNodeWithText("Senden").performClick()
        source.enqueue(ChatTurnEvent.RunStarted(RUN_ID))
        source.enqueue(ChatTurnEvent.TurnCompleted(pendingSteer = "Weiter mit Plan B"))
        source.complete()
        waitForFlowCompletion(source)

        // Der Turn ist beendet: die Recovery-Aktion erscheint über genau
        // dieselbe UI wie nach einer 409.
        composeRule.waitUntil(timeoutMillis = 5_000) {
            try {
                composeRule.onNodeWithTag("chatSteerSendAsNewMessage:0").assertIsDisplayed()
                true
            } catch (_: AssertionError) {
                false
            }
        }
        composeRule.onNodeWithText("Nachricht").assertIsEnabled()

        // Explizite Aktion: Text wandert in den Composer, ohne Versand.
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

        waitForText("Diese Session enthält noch keine Nachrichten.")
        composeRule.onNodeWithText("Nachricht").performTextInput("Steuer-Doppeltipp")
        composeRule.onNodeWithText("Senden").performClick()
        source.enqueue(ChatTurnEvent.RunStarted(RUN_ID))
        composeRule.onNodeWithTag("chatSteerInput").performTextInput("Doppeltipp")
        composeRule.onNodeWithTag("chatSteerSend").performClick()
        assertTrue(steerer.started.await(3, TimeUnit.SECONDS))

        // In der Anfrage: Eingabe und Aktion sind deaktiviert; der
        // schnelle zweite Tipp öffnet keinen zweiten Request.
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

        waitForText("Diese Session enthält noch keine Nachrichten.")
        composeRule.onNodeWithText("Nachricht").performTextInput("Steuer-Fehler")
        composeRule.onNodeWithText("Senden").performClick()
        source.enqueue(ChatTurnEvent.RunStarted(RUN_ID))
        composeRule.onNodeWithTag("chatSteerInput").performTextInput("Kursversuch")
        composeRule.onNodeWithTag("chatSteerSend").performClick()
        assertTrue(steerer.started.await(3, TimeUnit.SECONDS))

        // Generische Meldung ohne dynamische Details; der Text bleibt in
        // der Eingabe für einen bewussten manuellen Retry; die
        // Run-ID wird nirgends gerendert.
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

        // Der bewusste manuelle Retry sendet genau einen weiteren
        // Request (gleicher Text).
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

        waitForText("Diese Session enthält noch keine Nachrichten.")
        composeRule.onNodeWithText("Nachricht").performTextInput("Erster Lauf")
        composeRule.onNodeWithText("Senden").performClick()
        source.enqueue(ChatTurnEvent.RunStarted(RUN_ID))
        composeRule.onNodeWithTag("chatSteerInput").performTextInput("Alter Steuerentwurf")
        composeRule.onNodeWithTag("chatSteerSend").performClick()
        assertTrue(steerer.started.await(3, TimeUnit.SECONDS))
        // 409: Recovery-Text gesetzt.
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

        // Eine neue gewöhnliche Nachricht startet einen neuen Run: der
        // run-spezifische Steuerentwurf darf nicht in diesen Run
        // hineingleiten — aber der erhaltene Recovery-Text ist
        // Nutzereingabe und geht nicht verloren (Mission 008B).
        composeRule.onNodeWithText("Nachricht").performTextInput("Zweiter Lauf")
        composeRule.onNodeWithText("Senden").performClick()
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

        // Explizite Aktion: exakter Text in den Composer, ohne Versand.
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

        waitForText("Diese Session enthält noch keine Nachrichten.")
        composeRule.onNodeWithText("Nachricht").performTextInput("Bleibt-Stehen")
        composeRule.onNodeWithText("Senden").performClick()
        source.enqueue(ChatTurnEvent.RunStarted(RUN_ID))
        composeRule.onNodeWithTag("chatSteerInput").performTextInput("Bleibt stehen")
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

        // Terminal ohne `pending_steer`: der Nutzertext darf nicht
        // verloren gehen — die Recovery-Aktion bleibt sichtbar.
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
        // Die explizite Aktion verschiebt den Text, ohne ihn zu senden.
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

        waitForText("Diese Session enthält noch keine Nachrichten.")
        composeRule.onNodeWithText("Nachricht").performTextInput("Ende-in-Flight")
        composeRule.onNodeWithText("Senden").performClick()
        source.enqueue(ChatTurnEvent.RunStarted(RUN_ID))
        composeRule.onNodeWithTag("chatSteerInput").performTextInput("Halb weg")
        composeRule.onNodeWithTag("chatSteerSend").performClick()
        assertTrue(steerer.started.await(3, TimeUnit.SECONDS))

        // Der Run endet, während der Steuer-Request noch läuft: der
        // Server hat den Text vielleicht oder vielleicht nicht
        // eingereiht — er darf unter keinen Umständen verloren gehen,
        // sondern wandert in die Recovery-Zustandsmenge.
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
        // Explizite Aktion: exakter Text in den Composer, ohne Versand.
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

        waitForText("Diese Session enthält noch keine Nachrichten.")
        composeRule.onNodeWithText("Nachricht").performTextInput("Vier-Hunderteinundneunzig")
        composeRule.onNodeWithText("Senden").performClick()
        source.enqueue(ChatTurnEvent.RunStarted(RUN_ID))
        composeRule.onNodeWithTag("chatSteerInput").performTextInput("Rettet mich")
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
        // Turn beenden, damit der gewöhnliche Composer wieder editierbar ist.
        source.enqueue(ChatTurnEvent.TurnCompleted())
        source.complete()
        waitForFlowCompletion(source)

        // Der gewöhnliche Composer erhält einen nicht-leeren Entwurf: die
        // Recovery-Aktion muss deaktiviert werden und ein No-Op bleiben,
        // damit der Nutzertext nicht übertschrieben wird (Mission 008B).
        composeRule.onNodeWithText("Nachricht").performTextInput("Mein laufender Entwurf")
        assertEquals("Mein laufender Entwurf", draftText())
        composeRule.onNodeWithTag("chatSteerSendAsNewMessage:0").assertIsNotEnabled()

        // Klick auf die (deaktivierte) Aktion ändert nichts: Entwurf bleibt,
        // der Recovery-Text bleibt erhalten, der Block bleibt sichtbar.
        composeRule.onNodeWithTag("chatSteerSendAsNewMessage:0").performClick()
        assertEquals("der Composer darf nicht überschrieben werden", "Mein laufender Entwurf", draftText())
        assertEquals("genau ein Item", 1, recoveryItemCount())
        assertTrue("der erhaltene Text muss erhalten bleiben", recoveryItemTextPresent("Rettet mich"))

        // Erst wenn der Composer leer ist, ist die Aktion wieder möglich und
        // verschiebt genau das eine Item. `performTextClearance()` leert
        // das Feld wirklich (im Gegensatz zu `performTextInput("")`, das
        // ein No-Op ist), genau die Voraussetzung, die die Aktion
        // verlangt.
        composeRule.onNodeWithText("Nachricht").performTextClearance()
        composeRule.onNodeWithTag("chatSteerSendAsNewMessage:0").assertIsEnabled()
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

        waitForText("Diese Session enthält noch keine Nachrichten.")
        composeRule.onNodeWithText("Nachricht").performTextInput("Zwei-Erhaltung")
        composeRule.onNodeWithText("Senden").performClick()
        source.enqueue(ChatTurnEvent.RunStarted(RUN_ID))
        composeRule.onNodeWithTag("chatSteerInput").performTextInput("Steuer-A")
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
        // Terminal mit einem anderen `pending_steer`: zwei getrennte,
        // geordnete Items, nie zu einem String zusammengeführt.
        source.enqueue(ChatTurnEvent.TurnCompleted(pendingSteer = "Steuer-B"))
        source.complete()
        waitForFlowCompletion(source)

        assertEquals("zwei separate Items", 2, recoveryItemCount())
        assertTrue("das erste Item bleibt erhalten", recoveryItemTextPresent("Steuer-A"))
        assertTrue("das zweite Item bleibt erhalten", recoveryItemTextPresent("Steuer-B"))
        // Ein zusammengeführter String hätte genau eine Zeile
        // "Steuer-A\nSteuer-B" erzeugt — die darf nicht existieren.
        assertTrue(
            "no merged single item",
            composeRule.onAllNodesWithText("Steuer-A\nSteuer-B").fetchSemanticsNodes().isEmpty()
        )
        // Zwei getrennte, geordnete Items: beide sind sichtbar und nie
        // automatisch gesendet worden.
        assertEquals("nie automatisch gesendet", 1, source.calls.get())
    }

    @Test
    fun clickingTheSecondRecoveryActionMovesExactlyTheSecondItem() {
        // Regression für die Aktion-zu-Item-Identität (Mission 008B):
        // Jeder Recovery-Block hat seine EIGENE Aktion, verdrahtet auf
        // genau SEINE Position. Ein Klick auf den ZWEITEN Block darf
        // genau das zweite Original-Item in den (leeren) Composer
        // verschieben — niemals das erste. Das erste bleibt unverändert
        // in der Recovery-Menge; nichts wird automatisch gesendet.
        val steerer = FakeRunSteerRequester()
        val source = FakeTurnSource()
        steerer.release(RunSteerResult.NotAccepting(RUN_ID))
        setScreen(history(emptyList()), source, runSteerRequester = steerer)

        waitForText("Diese Session enthält noch keine Nachrichten.")
        composeRule.onNodeWithText("Nachricht").performTextInput("Zwei-Items")
        composeRule.onNodeWithText("Senden").performClick()
        source.enqueue(ChatTurnEvent.RunStarted(RUN_ID))
        composeRule.onNodeWithTag("chatSteerInput").performTextInput("Steuer-Erstes")
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
        // Terminal mit einem zweiten, anderen `pending_steer`: zwei
        // geordnete Items (0 = 409-Text, 1 = Pending-Text).
        source.enqueue(ChatTurnEvent.TurnCompleted(pendingSteer = "Steuer-Zweites"))
        source.complete()
        waitForFlowCompletion(source)

        assertEquals("zwei separate Items", 2, recoveryItemCount())
        assertTrue("das erste Item bleibt erhalten", recoveryItemTextPresent("Steuer-Erstes"))
        assertTrue("das zweite Item bleibt erhalten", recoveryItemTextPresent("Steuer-Zweites"))

        // Der Composer ist leer, also sind beide Aktionen aktiv.
        assertTrue("Composer ist leer", draftText().isNullOrBlank())
        composeRule.onNodeWithTag("chatSteerSendAsNewMessage:1").assertIsEnabled()

        // Klick auf die Aktion des ZWEITEN Blocks: genau der zweite
        // Original-Text wandert in den Composer, das erste Item bleibt
        // in der Recovery-Menge und behält seine Identität.
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
        // Kein automatischer Versand: nur der eine ursprüngliche
        // Chat-POST existiert.
        assertEquals("nie automatisch gesendet", 1, source.calls.get())
    }

    @Test
    fun pendingSteerEqualToInFlightDraftYieldsExactlyOneRecoveryItem() {
        val steerer = FakeRunSteerRequester()
        val source = FakeTurnSource()
        steerer.gate = Channel(Channel.UNLIMITED)
        setScreen(history(emptyList()), source, runSteerRequester = steerer)

        waitForText("Diese Session enthält noch keine Nachrichten.")
        composeRule.onNodeWithText("Nachricht").performTextInput("Doppelt-Kein")
        composeRule.onNodeWithText("Senden").performClick()
        source.enqueue(ChatTurnEvent.RunStarted(RUN_ID))
        // Eingabe mit führenden/abschließenden Leerraum: der Server
        // liefert normalisiert den getrimmten Wire-Text zurück. Der
        // Duplicate-Schutz muss den exakten, validierten/gesendeten
        // Text (getrimmt) vergleichen, NICHT den rohen, nicht-getrimmten
        // `steerDraft` — sonst entspräche `"  Gleich  "` != `"Gleich"`
        // und es entstünde ein falsches zweites Item (Mission 008B).
        composeRule.onNodeWithTag("chatSteerInput").performTextInput("  Gleich  ")
        composeRule.onNodeWithTag("chatSteerSend").performClick()
        assertTrue(steerer.started.await(3, TimeUnit.SECONDS))
        // Exakt der validierte/gesendete (getrimmte) Wire-Text.
        assertEquals("Gleich", steerer.text.get())

        // Der Run endet, während der Steuer-Request in der Anfrage hängt,
        // und sein `pending_steer` ist derselbe Request — hier als der
        // normalisierte Wire-Text `"Gleich"`. Genau EIN Recovery-Item,
        // keine doppelte Aufnahme desselben Request.
        source.enqueue(ChatTurnEvent.TurnCompleted(pendingSteer = "Gleich"))
        source.complete()
        waitForFlowCompletion(source)

        assertEquals("genau ein Item für denselben Request", 1, recoveryItemCount())
        assertTrue(
            "der normalisierte Wire-Text ist erhalten",
            recoveryItemTextPresent("Gleich")
        )
        // Explizite Aktion: das eine Item wandert in den Composer,
        // ohne Versand — als der normalisierte Wire-Text.
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
                    chatSource = if (key.value == "first-session") firstSource else secondSource,
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

        waitForText("Diese Session enthält noch keine Nachrichten.")
        composeRule.onNodeWithText("Nachricht").performTextInput("Erste Session")
        composeRule.onNodeWithText("Senden").performClick()
        assertTrue(firstSource.opened.await(3, TimeUnit.SECONDS))
        firstSource.enqueue(ChatTurnEvent.RunStarted(RUN_ID))
        composeRule.onNodeWithTag("chatSteerInput").performTextInput("Steht in Session 1")
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

        // Sessionwechsel: die Recovery-Zustandsmenge der alten Session
        // (profil/session-schlüssel) muss vollständig verschwinden.
        key.value = "second-session"
        composeRule.waitForIdle()
        waitForText("Diese Session enthält noch keine Nachrichten.")
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

    /**
     * Number of recoverable steer items currently rendered (Mission 008B).
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
     * True while the exact item [text] is rendered as a recovery item
     * (Mission 008B). Scoped to the item text nodes tagged
     * `chatSteerRecoveryText`, so the same string sitting in the ordinary
     * composer (e.g. right after an explicit move) can never produce a
     * false positive. Used to assert that a preserved recovery item is
     * still present after a run boundary.
     */
    private fun recoveryItemTextPresent(text: String): Boolean {
        // `SemanticsProperties.Text` holds a List<AnnotatedString>; read the
        // actual annotated text of each item node rather than toString() on
        // the list.
        return composeRule
            .onAllNodesWithTag("chatSteerRecoveryText")
            .fetchSemanticsNodes()
            .any { node ->
                node.config[SemanticsProperties.Text]
                    ?.any { it.text == text }
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
     * Synthetic stop fake (Mission 007B): no network, no credentials.
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
     * Synthetic steer fake (Mission 008B): no network, no credentials.
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
