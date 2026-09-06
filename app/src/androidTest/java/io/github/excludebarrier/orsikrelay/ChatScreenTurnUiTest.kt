package io.github.excludebarrier.orsikrelay

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import io.github.excludebarrier.orsikrelay.domain.chat.ChatStreamError
import io.github.excludebarrier.orsikrelay.domain.chat.ChatTurnEvent
import io.github.excludebarrier.orsikrelay.domain.chat.StreamingChatSource
import io.github.excludebarrier.orsikrelay.domain.message.HermesChatMessage
import io.github.excludebarrier.orsikrelay.domain.message.MessageHistoryResult
import io.github.excludebarrier.orsikrelay.domain.message.MessageHistorySource
import io.github.excludebarrier.orsikrelay.domain.message.MessagePage
import io.github.excludebarrier.orsikrelay.domain.message.MessageRole
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

        source.enqueue(ChatTurnEvent.RunStarted)
        // Erst RunStarted leert den Entwurf.
        composeRule.waitUntil(timeoutMillis = 5_000) {
            draftText().isNullOrBlank()
        }
        composeRule.waitForIdle()
        assertEquals(1, source.calls.get())

        source.enqueue(ChatTurnEvent.TurnCompleted)
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
            ChatTurnEvent.RunStarted,
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
            ChatTurnEvent.TurnCompleted
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
            ChatTurnEvent.RunStarted,
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
            ChatTurnEvent.TurnCompleted
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
            ChatTurnEvent.RunStarted,
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
            ChatTurnEvent.RunStarted,
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
                emit(ChatTurnEvent.RunStarted)
                emit(ChatTurnEvent.AssistantStarted)
                emit(ChatTurnEvent.AssistantCompleted("Sofort fertig"))
                emit(ChatTurnEvent.TurnCompleted)
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
    fun sessionSwitchCancelsRunningCollectorAndResetsEphemeralState() {
        val firstSource = FakeTurnSource()
        val secondSource = FakeTurnSource()
        val key = androidx.compose.runtime.mutableStateOf("first-session")
        composeRule.setContent {
            HermesTheme {
                ChatScreen(
                    historySource = history(emptyList()),
                    chatSource = if (key.value == "first-session") firstSource else secondSource,
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
        onBack: () -> Unit = {}
    ) {
        composeRule.setContent {
            HermesTheme {
                ChatScreen(
                    historySource = source,
                    chatSource = chatSource,
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
        composeRule.waitUntil(timeoutMillis = 5_000) { source.completed.get() }
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
}
