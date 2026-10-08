package io.github.excludebarrier.orsikrelay

import android.content.Context
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
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import io.github.excludebarrier.orsikrelay.data.chat.ChatDraftRepository
import io.github.excludebarrier.orsikrelay.data.chat.SharedPreferencesChatDraftRepository
import io.github.excludebarrier.orsikrelay.data.chat.SharedPreferencesChatDraftStore
import io.github.excludebarrier.orsikrelay.data.chat.chatDraftKey
import io.github.excludebarrier.orsikrelay.domain.chat.ChatStreamError
import io.github.excludebarrier.orsikrelay.domain.chat.ChatTurnEvent
import io.github.excludebarrier.orsikrelay.domain.chat.StreamingChatSource
import io.github.excludebarrier.orsikrelay.domain.message.MessageHistoryResult
import io.github.excludebarrier.orsikrelay.domain.message.MessageHistorySource
import io.github.excludebarrier.orsikrelay.domain.message.MessagePage
import io.github.excludebarrier.orsikrelay.domain.run.RunStopRequester
import io.github.excludebarrier.orsikrelay.domain.run.RunStopResult
import io.github.excludebarrier.orsikrelay.domain.run.RunSteerRequester
import io.github.excludebarrier.orsikrelay.domain.run.RunSteerResult
import io.github.excludebarrier.orsikrelay.domain.run.RunForegroundServiceStarter
import io.github.excludebarrier.orsikrelay.domain.run.RunSessionController
import io.github.excludebarrier.orsikrelay.draft.ControllableDraftRepository
import io.github.excludebarrier.orsikrelay.draft.InMemoryChatDraftRepository
import io.github.excludebarrier.orsikrelay.ui.chat.ChatScreen
import io.github.excludebarrier.orsikrelay.ui.chat.NoOpNotificationCoordinator
import io.github.excludebarrier.orsikrelay.ui.theme.HermesTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Compose/instrumentation tests for the ordinary composer-draft
 * persistence (Mission 009A). No network, no credentials.
 *
 * Most tests use the in-memory draft seam to prove the UI contract
 * (restore, isolation, clear-on-success, keep-on-failure, stale-load
 * protection, steering/recovery never restored). The recreation test
 * uses the REAL production persistence implementation
 * ([SharedPreferencesChatDraftRepository] over the app-private
 * [SharedPreferencesChatDraftStore]) to prove the draft survives a
 * COMPOSITION + repository-instance recreation (subtree re-keying: the
 * disposal/rebuild boundary a recreation produces). It is strictly NOT a
 * real Activity/config recreation: no `onSaveInstanceState` /
 * `onConfigurationChanged` callbacks are exercised (see the test's
 * honest-scope comment and internal task log, NICHT VERIFIZIERT).
 */
class ChatDraftPersistenceUiTest {

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

    // ------------------------------------------------------------------
    // Restore
    // ------------------------------------------------------------------

    @Test
    fun seededDraftIsRestoredIntoTheOrdinaryComposer() {
        val drafts = InMemoryChatDraftRepository()
        drafts.seed(PROFILE, SESSION, "Wiederhergestellter Entwurf")
        setScreen(drafts)

        waitForComposerText("Wiederhergestellter Entwurf")
        composeRule.onNodeWithText("Senden").assertIsEnabled()
    }

    @Test
    fun draftSurvivesARecreatedCompositionWithTheRealProductionStore() {
        // HONEST SCOPE (parent review, 009A): this is a COMPOSITION +
        // repository-instance recreation, NOT a real Activity/config
        // recreation. Bumping the generation re-keys the subtree, which
        // disposes and rebuilds it: all `remember` slots reset and all
        // effects re-run — the same disposal/rebuild boundary an Activity
        // recreation produces. What this test does NOT exercise are the
        // actual Android lifecycle callbacks (`onSaveInstanceState` /
        // `onConfigurationChanged`); a genuine ActivityScenario
        // config-recreation run remains NICHT VERIFIZIERT (see internal task log).
        // REAL production implementation: app-private SharedPreferences on
        // the real device storage.
        val context = context()
        val preferencesName = "hermes_chat_draft_recreation_${java.util.UUID.randomUUID()}"
        val firstRepository = SharedPreferencesChatDraftRepository(
            store = SharedPreferencesChatDraftStore(context, preferencesName),
        )
        // One composition for the whole test; [current] selects the
        // generation. Bumping the generation re-keys the subtree (the
        // disposal/rebuild boundary described above).
        val current = androidx.compose.runtime.mutableStateOf<ScreenInstance?>(
            ScreenInstance(1, firstRepository)
        )
        composeRule.setContent {
            HermesTheme {
                val instance = current.value
                if (instance != null) {
                    androidx.compose.runtime.key(instance.generation) {
                        ChatScreen(
                            historySource = historySource,
                            runController = controllerFor(neverEmits),
                            runStopRequester = neverReachedStop,
                            runSteerRequester = neverReachedSteer,
                            chatDraftRepository = instance.repository,
                            profileId = PROFILE,
                            sessionId = SESSION,
                            onBack = {}
                        )
                    }
                }
            }
        }
        waitForEmpty()

        // Typing schedules the debounced, latest-value write; advancing the
        // main clock past the debounce window lets it run.
        composeRule.onNodeWithText("Nachricht").performTextInput("Überlebt Neuaufbau")
        composeRule.mainClock.advanceTimeBy(700)
        composeRule.waitForIdle()

        // The real store now holds the exact text on app-private disk.
        val onDisk = waitForValue {
            context.getSharedPreferences(
                preferencesName,
                Context.MODE_PRIVATE
            ).getString("chat.draft.v1." + chatDraftKey(PROFILE, SESSION), null)
        }
        assertEquals("Überlebt Neuaufbau", onDisk)

        // Composition recreation: fresh repository instance over the SAME
        // app-private preferences file, new generation.
        current.value = ScreenInstance(
            2,
            SharedPreferencesChatDraftRepository(
                store = SharedPreferencesChatDraftStore(context, preferencesName),
            )
        )
        composeRule.waitForIdle()

        waitForComposerText("Überlebt Neuaufbau")
        context.deleteSharedPreferences(preferencesName)
    }

    // ------------------------------------------------------------------
    // Isolation
    // ------------------------------------------------------------------

    @Test
    fun sessionAndProfileSwitchesAreFullyIsolated() {
        val drafts = InMemoryChatDraftRepository()
        drafts.seed(PROFILE, "session-a", "Entwurf A")
        drafts.seed("other-profile", SESSION, "Entwurf Profil")
        val key = androidx.compose.runtime.mutableStateOf(PROFILE to "session-a")
        composeRule.setContent {
            HermesTheme {
                ChatScreen(
                    historySource = historySource,
                    runController = controllerFor(neverEmits),
                    runStopRequester = neverReachedStop,
                    runSteerRequester = neverReachedSteer,
                    chatDraftRepository = drafts,
                    profileId = key.value.first,
                    sessionId = key.value.second,
                    onBack = {}
                )
            }
        }

        waitForComposerText("Entwurf A")

        // Switch to the same session under a DIFFERENT profile: no bleed.
        key.value = "other-profile" to SESSION
        composeRule.waitForIdle()
        waitForComposerText("Entwurf Profil")

        // Switch back: the first draft is restored exactly, and the other
        // key is untouched.
        key.value = PROFILE to "session-a"
        composeRule.waitForIdle()
        waitForComposerText("Entwurf A")

        assertEquals("Entwurf Profil", drafts.stored("other-profile", SESSION))
        assertEquals("Entwurf A", drafts.stored(PROFILE, "session-a"))
    }

    // ------------------------------------------------------------------
    // Clear-on-success, keep-on-failure
    // ------------------------------------------------------------------

    @Test
    fun successfulOrdinarySendClearsComposerAndPersistence() {
        val drafts = InMemoryChatDraftRepository()
        val source = FakeTurnSource()
        setScreen(drafts, runController = controllerFor(source))

        waitForEmpty()
        composeRule.onNodeWithText("Nachricht").performTextInput("Wird gesendet")
        composeRule.onNodeWithText("Senden").performClick()
        assertTrue(source.opened.await(3, TimeUnit.SECONDS))

        // The server-confirmed handoff clears the composer AND the stored
        // draft; the just-sent text is never re-persisted.
        source.enqueue(ChatTurnEvent.RunStarted(RUN_ID))
        waitUntilComposerBlank()
        composeRule.waitForIdle()
        assertNull("the stored draft must be cleared", drafts.stored(PROFILE, SESSION))

        source.enqueue(ChatTurnEvent.TurnCompleted())
        source.complete()
        waitForFlowCompletion(source)
    }

    @Test
    fun failedOrBlockedSendKeepsDraftAndBlocksTheComposerUnderUncertainty() {
        val drafts = InMemoryChatDraftRepository()
        val source = FakeTurnSource()
        setScreen(drafts, runController = controllerFor(source))

        waitForEmpty()
        composeRule.onNodeWithText("Nachricht").performTextInput("Bleibt erhalten")
        composeRule.onNodeWithText("Senden").performClick()
        assertTrue(source.opened.await(3, TimeUnit.SECONDS))

        // P7: the initial POST failed with NO confirmed run ID, but it may
        // already have been accepted server-side. The outcome is therefore
        // UNKNOWN: the draft (composer AND disk) survives, the composer stays
        // locked (no second POST) and the honest uncertain notice is shown.
        // Without a confirmed run ID there is no safe reconciliation action.
        source.enqueue(ChatTurnEvent.TurnFailed(ChatStreamError.Network))
        source.complete()
        waitForFlowCompletion(source)

        assertEquals("Bleibt erhalten", draftText())
        assertEquals("Bleibt erhalten", drafts.stored(PROFILE, SESSION))
        composeRule.onNodeWithText("Senden").assertIsDisplayed()
        composeRule.onNodeWithText("Senden").assertIsNotEnabled()
        composeRule.onNodeWithText("Nachricht").assertIsNotEnabled()
        assertTrue(
            "the honest uncertain notice must be shown, never a stopped run",
            composeRule.onAllNodesWithTag("chat_run_uncertain")
                .fetchSemanticsNodes().isNotEmpty()
        )
        assertTrue(
            "no run ID was confirmed, so no reconciliation action is offered",
            composeRule.onAllNodesWithTag("chat_run_reconcile")
                .fetchSemanticsNodes().isEmpty()
        )
        assertEquals("no automatic second POST", 1, source.calls.get())
    }

    // ------------------------------------------------------------------
    // Steer/recovery never restored
    // ------------------------------------------------------------------

    @Test
    fun steerInputAndRecoveryAreNeverRestoredByTheDraftStore() {
        val drafts = InMemoryChatDraftRepository()
        val source = FakeTurnSource()
        val steerer = FakeRunSteerRequester()
        steerer.immediateResult = RunSteerResult.NotAccepting(RUN_ID)
        val current = androidx.compose.runtime.mutableStateOf<ScreenInstance?>(
            ScreenInstance(1, drafts, source, steerer)
        )
        composeRule.setContent {
            HermesTheme {
                val instance = current.value
                if (instance != null) {
                    androidx.compose.runtime.key(instance.generation) {
                        ChatScreen(
                            historySource = historySource,
                            runController = controllerFor(instance.chatSource),
                            runStopRequester = neverReachedStop,
                            runSteerRequester = instance.steerer,
                            chatDraftRepository = instance.repository,
                            profileId = PROFILE,
                            sessionId = SESSION,
                            onBack = {}
                        )
                    }
                }
            }
        }
        waitForEmpty()
        composeRule.onNodeWithText("Nachricht").performTextInput("Ordinary message")
        composeRule.onNodeWithText("Senden").performClick()
        assertTrue(source.opened.await(3, TimeUnit.SECONDS))
        source.enqueue(ChatTurnEvent.RunStarted(RUN_ID))
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("chatSteerInput").fetchSemanticsNodes().isNotEmpty()
        }

        // A 409 steer becomes a recoverable item, NOT the ordinary draft.
        composeRule.onNodeWithTag("chatSteerInput").performTextInput("Steuer-Text")
        composeRule.onNodeWithTag("chatSteerSend").performClick()
        assertTrue(steerer.started.await(3, TimeUnit.SECONDS))
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Als neue Nachricht senden")
                .fetchSemanticsNodes().isNotEmpty()
        }

        // Only the ORDINARY draft is persisted by this seam — and it is
        // empty now (cleared on RunStarted). The steer text and the
        // recovery item are never stored.
        assertTrue(
            "steer/recovery text must never be persisted",
            drafts.storedDrafts.values.none { it.contains("Steuer-Text") }
        )
        assertNull(drafts.stored(PROFILE, SESSION))

        // Recreation: the recovery item is run state, so it is NOT
        // restored by the draft store; the composer comes back empty.
        current.value = ScreenInstance(2, drafts, source, steerer)
        composeRule.waitForIdle()
        waitForEmpty()
        assertTrue(
            "recovery must not be restored by the draft store",
            composeRule.onAllNodesWithText("Als neue Nachricht senden")
                .fetchSemanticsNodes().isEmpty()
        )
    }

    // ------------------------------------------------------------------
    // Stale/delayed load protection
    // ------------------------------------------------------------------

    @Test
    fun staleDelayedLoadForOldKeyCannotOverwriteTheNewComposer() {
        val repo = ControllableDraftRepository()
        // The NavHost keeps one ChatScreen whose sessionId parameter changes
        // on a session switch. [session] models exactly that: a `key` change
        // disposes the old subtree (cancelling its in-flight load) and builds
        // the new one.
        val session = androidx.compose.runtime.mutableStateOf("old-session")
        composeRule.setContent {
            HermesTheme {
                androidx.compose.runtime.key(session.value) {
                    ChatScreen(
                        historySource = historySource,
                        runController = controllerFor(neverEmits),
                        runStopRequester = neverReachedStop,
                        runSteerRequester = neverReachedSteer,
                        chatDraftRepository = repo,
                        profileId = PROFILE,
                        sessionId = session.value,
                        onBack = {}
                    )
                }
            }
        }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            repo.loadStarted(PROFILE, "old-session")
        }

        // Switch to a NEW session while the old load is still in flight.
        // The keyed load effect is canceled; the stale value must never
        // land in the new screen's composer.
        session.value = "new-session"
        composeRule.waitForIdle()
        waitForEmpty()

        // Now the delayed old-key load completes.
        repo.release(PROFILE, "old-session", "Alter veralteter Text")
        composeRule.waitForIdle()

        // The new screen's composer must NOT hold the stale old-key text.
        assertFalse(
            "a stale old-key load must never overwrite the new composer",
            composeRule.onAllNodesWithText("Alter veralteter Text")
                .fetchSemanticsNodes().isNotEmpty()
        )
        assertNull(
            "the new key has no stored draft",
            repo.storedValue(PROFILE, "new-session")
        )
    }

    @Test
    fun sameKeyDelayedLoadCannotOverwriteANewerTypedDraft() {
        // Same-key restore race (parent review): the (profileId, sessionId)
        // load is held open; BEFORE it returns, the user types a newer value
        // into the ordinary composer. When the delayed same-key load finally
        // releases an OLDER stored value, it must not overwrite the newer
        // local intent — the composer keeps the typed text.
        val repo = ControllableDraftRepository()
        composeRule.setContent {
            HermesTheme {
                ChatScreen(
                    historySource = historySource,
                    runController = controllerFor(neverEmits),
                    runStopRequester = neverReachedStop,
                    runSteerRequester = neverReachedSteer,
                    chatDraftRepository = repo,
                    profileId = PROFILE,
                    sessionId = SESSION,
                    onBack = {}
                )
            }
        }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            repo.loadStarted(PROFILE, SESSION)
        }
        waitForEmpty()

        // Newer local intent while the same-key load is still in flight.
        composeRule.onNodeWithText("Nachricht").performTextInput("Neuer Eingabe")

        // The delayed SAME-key load completes with an OLDER stored value.
        repo.release(PROFILE, SESSION, "Alter gespeicherter Wert")
        composeRule.waitForIdle()

        assertEquals(
            "the newer typed value must survive the delayed same-key load",
            "Neuer Eingabe",
            draftText()
        )
        assertFalse(
            "a delayed same-key load must never overwrite newer local intent",
            composeRule.onAllNodesWithText("Alter gespeicherter Wert")
                .fetchSemanticsNodes().isNotEmpty()
        )
    }

    @Test
    fun sameKeyDelayedLoadCannotResurrectAClearedDraft() {
        // Clear-after-success (parent review): the message is sent and the
        // server-confirmed handoff (`RunStarted`) clears the composer AND the
        // stored state — while the SAME-key restore load is still held. When
        // the delayed load now returns the just-sent value, it must not
        // resurrect the cleared state.
        val repo = ControllableDraftRepository()
        val source = FakeTurnSource()
        composeRule.setContent {
            HermesTheme {
                ChatScreen(
                    historySource = historySource,
                    runController = controllerFor(source),
                    runStopRequester = neverReachedStop,
                    runSteerRequester = neverReachedSteer,
                    chatDraftRepository = repo,
                    profileId = PROFILE,
                    sessionId = SESSION,
                    onBack = {}
                )
            }
        }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            repo.loadStarted(PROFILE, SESSION)
        }
        waitForEmpty()

        composeRule.onNodeWithText("Nachricht").performTextInput("Wird gesendet")
        composeRule.onNodeWithText("Senden").performClick()
        assertTrue(source.opened.await(3, TimeUnit.SECONDS))

        // The successful send handoff clears composer + stored state, with
        // the same-key load still in flight.
        source.enqueue(ChatTurnEvent.RunStarted(RUN_ID))
        waitUntilComposerBlank()

        // The delayed SAME-key load now returns the just-sent value.
        repo.release(PROFILE, SESSION, "Wird gesendet")
        composeRule.waitForIdle()

        // The cleared state must win: the composer stays blank, the stored
        // state was cleared by the screen, and the just-sent text is never
        // resurrected.
        waitUntilComposerBlank()
        assertTrue(
            "the screen must have cleared the stored state on the successful handoff",
            repo.wasCleared(PROFILE, SESSION)
        )
        assertEquals(
            "the composer must stay blank after the delayed same-key load",
            "",
            draftText()
        )

        source.enqueue(ChatTurnEvent.TurnCompleted())
        source.complete()
        waitForFlowCompletion(source)
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun setScreen(
        drafts: ChatDraftRepository,
        runController: RunSessionController = controllerFor(neverEmits),
        runSteerRequester: RunSteerRequester = neverReachedSteer
    ) {
        composeRule.setContent {
            HermesTheme {
                ChatScreen(
                    historySource = historySource,
                    runController = runController,
                    runStopRequester = neverReachedStop,
                    runSteerRequester = runSteerRequester,
                    chatDraftRepository = drafts,
                    profileId = PROFILE,
                    sessionId = SESSION,
                    onBack = {}
                )
            }
        }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Diese Session enthält noch keine Nachrichten.")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()
    }

    private fun waitForEmpty() {
        composeRule.waitUntil(timeoutMillis = 5_000) { draftText().isNullOrEmpty() }
        composeRule.waitForIdle()
    }

    private fun waitForComposerText(text: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) { draftText() == text }
        composeRule.waitForIdle()
    }

    private fun waitUntilComposerBlank() {
        composeRule.waitUntil(timeoutMillis = 5_000) { draftText().isNullOrBlank() }
    }

    private fun draftText(): String? {
        return composeRule
            .onAllNodesWithText("Nachricht")
            .fetchSemanticsNodes()
            .firstOrNull { it.config.contains(SemanticsProperties.EditableText) }
            ?.config[SemanticsProperties.EditableText]
            ?.text
            ?.toString()
    }

    private fun waitForFlowCompletion(source: FakeTurnSource) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            source.completed.get() || source.canceled.count == 0L
        }
        composeRule.waitForIdle()
    }

    private fun waitForValue(block: () -> String?): String? {
        var result: String? = null
        composeRule.waitUntil(timeoutMillis = 5_000) {
            result = block()
            result != null
        }
        return result
    }

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    /**
     * One composed ChatScreen generation (Mission 009A). Bumping
     * [generation] re-keys the subtree in a test's composition, disposing
     * the old screen and building the new one — the same disposal/rebuild
     * boundary an Activity/config recreation or a session switch produces.
     */
    private data class ScreenInstance(
        val generation: Int,
        val repository: ChatDraftRepository,
        val chatSource: StreamingChatSource = neverEmits,
        val steerer: RunSteerRequester = neverReachedSteer
    )

    private companion object {
        const val PROFILE = "fixture-profile-id"
        const val SESSION = "fixture-session-id"
        const val RUN_ID = "run_synthetic"

        val historySource: MessageHistorySource = MessageHistorySource { _, _ ->
            MessageHistoryResult.Success(MessagePage(emptyList()))
        }

        val neverEmits = StreamingChatSource { _, _, _ -> emptyFlow() }

        val neverReachedStop = object : RunStopRequester {
            override suspend fun stopRun(profileId: String, runId: String): RunStopResult {
                throw AssertionError("run-stop must not be reachable in draft tests")
            }
        }

        val neverReachedSteer = object : RunSteerRequester {
            override suspend fun steerRun(
                profileId: String,
                runId: String,
                text: String
            ): RunSteerResult {
                throw AssertionError("run-steer must not be reachable in draft tests")
            }
        }
    }

    /**
     * Synthetic streaming fake: [streamTurn] is latched, events are
     * enqueued and closed by the test. No network, no credentials.
     */
    private class FakeTurnSource : StreamingChatSource {
        val calls = AtomicInteger()
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
            activeChannel?.let { channel -> events.forEach { channel.trySend(it) } }
        }

        fun complete() {
            activeChannel?.close()
        }
    }

    /**
     * Synthetic steer fake: returns a pre-set result immediately. No
     * network, no credentials.
     */
    private class FakeRunSteerRequester : RunSteerRequester {
        val calls = AtomicInteger()
        val started = CountDownLatch(1)
        @Volatile
        var immediateResult: RunSteerResult = RunSteerResult.Queued(RUN_ID)

        override suspend fun steerRun(
            profileId: String,
            runId: String,
            text: String
        ): RunSteerResult {
            calls.incrementAndGet()
            started.countDown()
            return immediateResult
        }
    }
}
