package io.github.excludebarrier.orsikrelay

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import io.github.excludebarrier.orsikrelay.data.session.StartupProfile
import io.github.excludebarrier.orsikrelay.domain.chat.ChatTurnEvent
import io.github.excludebarrier.orsikrelay.domain.chat.StreamingChatSource
import io.github.excludebarrier.orsikrelay.domain.message.HermesChatMessage
import io.github.excludebarrier.orsikrelay.domain.message.MessageHistoryResult
import io.github.excludebarrier.orsikrelay.domain.message.MessageHistorySource
import io.github.excludebarrier.orsikrelay.domain.message.MessagePage
import io.github.excludebarrier.orsikrelay.domain.message.MessageRole
import io.github.excludebarrier.orsikrelay.domain.session.HermesSession
import io.github.excludebarrier.orsikrelay.domain.session.SessionListResult
import io.github.excludebarrier.orsikrelay.domain.session.SessionPage
import io.github.excludebarrier.orsikrelay.domain.session.SessionPageSource
import io.github.excludebarrier.orsikrelay.navigation.AppScreen
import io.github.excludebarrier.orsikrelay.navigation.HermesNavHost
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
 * Navigation test for the live turn slice (Mission 006A, Batch 2):
 * the chat screen receives the exact synthetic profile/session/message
 * values through the existing secure route, and back during a running
 * turn cancels the injected synthetic stream before returning to the
 * session list. No network, no real hosts, no credentials.
 */
class ChatTurnNavigationUiTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun submitReceivesExactValuesAndBackDuringTurnCancelsStream() {
        val receivedProfile = AtomicReference<String>()
        val receivedSession = AtomicReference<String>()
        val receivedMessage = AtomicReference<String>()
        val turnSource = FakeTurnSource()
        val sessionId = "fixture/turn?sess&ion ü"
        val historyCalls = AtomicInteger()
        val sessions = SessionPageSource { _, _, _ ->
            SessionListResult.Success(
                SessionPage(
                    sessions = listOf(
                        HermesSession(
                            id = sessionId,
                            title = "Turn-Session",
                            source = null,
                            lastActiveEpochSeconds = null,
                            messageCount = 1,
                            pinned = false
                        )
                    ),
                    hasMore = false
                )
            )
        }
        val history = MessageHistorySource { profileId, selectedSessionId ->
            historyCalls.incrementAndGet()
            receivedProfile.set(profileId)
            receivedSession.set(selectedSessionId)
            MessageHistoryResult.Success(
                MessagePage(
                    listOf(
                        HermesChatMessage(
                            id = 1,
                            role = MessageRole.ASSISTANT,
                            content = "Fixture-Chatinhalt",
                            timestampSeconds = null,
                            toolName = null
                        )
                    )
                )
            )
        }

        composeRule.setContent {
            HermesTheme {
                HermesNavHost(
                    startDestination = AppScreen.Onboarding,
                    sessionPageSource = sessions,
                    messageHistorySource = history,
                    streamingChatSource = turnSource,
                    startupProfileResolver = { StartupProfile.Ready(PROFILE_ID) }
                )
            }
        }

        waitForText("Turn-Session")
        composeRule.onNodeWithText("Turn-Session").performClick()
        waitForText("Fixture-Chatinhalt")

        // Submit über die bestehende sichere Route: exakte Werte.
        composeRule
            .onNodeWithText("Nachricht")
            .performTextInput("Synthese 1")
        composeRule.onNodeWithText("Senden").performClick()
        assertTrue(turnSource.opened.await(3, TimeUnit.SECONDS))
        assertEquals(PROFILE_ID, turnSource.profileId.get())
        assertEquals(sessionId, turnSource.sessionId.get())
        assertEquals("Synthese 1", turnSource.message.get())

        // Back während des laufenden Turns bricht den Stream ab, bevor
        // die Navigation zurückführt: Die Sessionliste erscheint erst
        // nach der Cancellation, denn `onBack` wird erst nach
        // `cancelAndJoin` aufgerufen.
        composeRule.onNodeWithText("Zurück").performClick()
        waitForText("Turn-Session")
        assertTrue(
            "the running stream must be canceled before the back navigation",
            turnSource.canceled.await(3, TimeUnit.SECONDS)
        )
        assertEquals("exactly one stream", 1, turnSource.calls.get())
        assertEquals(1, historyCalls.get())
    }

    /**
     * System / gesture back during a running turn (Mission 006A acceptance
     * point 2). A hardware BACK key (or predictive-back gesture) on the
     * activity is delivered to the [androidx.activity.OnBackPressedDispatcher];
     * the chat screen's Compose `BackHandler` is registered into that same
     * dispatcher. `ComponentActivity.onBackPressed()` invokes the dispatcher,
     * so this is the faithful instrumentation seam for the system back event
     * (the compose ui-test 1.9.0 resolved here has no `performSystemBack`).
     *
     * The system back must: cancel the running collector (which closes the
     * underlying call), navigate safely back to the session list, and leave
     * no lifecycle crash or further UI state behind.
     */
    @Test
    fun systemBackDuringRunningTurnCancelsStreamAndReturnsToSessionList() {
        val turnSource = FakeTurnSource()
        val sessionId = "fixture/system-back-session"
        val sessions = SessionPageSource { _, _, _ ->
            SessionListResult.Success(
                SessionPage(
                    sessions = listOf(
                        HermesSession(
                            id = sessionId,
                            title = "SystemBack-Session",
                            source = null,
                            lastActiveEpochSeconds = null,
                            messageCount = 1,
                            pinned = false
                        )
                    ),
                    hasMore = false
                )
            )
        }
        val history = MessageHistorySource { _, _ ->
            MessageHistoryResult.Success(MessagePage(emptyList()))
        }

        composeRule.setContent {
            HermesTheme {
                HermesNavHost(
                    startDestination = AppScreen.Onboarding,
                    sessionPageSource = sessions,
                    messageHistorySource = history,
                    streamingChatSource = turnSource,
                    startupProfileResolver = { StartupProfile.Ready(PROFILE_ID) }
                )
            }
        }

        waitForText("SystemBack-Session")
        composeRule.onNodeWithText("SystemBack-Session").performClick()
        // We are now on the chat screen.
        waitForText("Chat")

        // Start a turn that stays open (the fake never emits a terminal
        // event and never completes on its own).
        composeRule.onNodeWithText("Nachricht").performTextInput("System back")
        composeRule.onNodeWithText("Senden").performClick()
        assertTrue(
            "stream was not started before the system back",
            turnSource.opened.await(3, TimeUnit.SECONDS)
        )
        assertEquals("exactly one stream", 1, turnSource.calls.get())

        // The activity must still be alive (resumed) before the back.
        assertTrue("activity should not be finished before back", !composeRule.activity.isFinishing)

        // Fire the system back event on the main thread. This goes
        // through the OnBackPressedDispatcher to the chat screen's
        // BackHandler.
        composeRule.runOnIdle {
            composeRule.activity.onBackPressed()
        }

        // The navigation back to the session list must complete, which
        // proves onBack() ran (after the cancellation) and the activity
        // was NOT finished (a lifecycle crash or finishing activity would
        // prevent the session list from appearing).
        waitForText("SystemBack-Session")

        // The running collector must have been canceled.
        assertTrue(
            "the running stream must be canceled by the system back",
            turnSource.canceled.await(3, TimeUnit.SECONDS)
        )
        // The activity is still usable (not crashed / not finished).
        assertTrue("activity must still be usable after system back", !composeRule.activity.isFinishing)
        assertEquals("exactly one stream", 1, turnSource.calls.get())
    }

    private fun waitForText(text: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()
    }

    private class FakeTurnSource : StreamingChatSource {
        val calls = AtomicInteger()
        val profileId = AtomicReference<String>()
        val sessionId = AtomicReference<String>()
        val message = AtomicReference<String>()
        val opened = CountDownLatch(1)
        val canceled = CountDownLatch(1)
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
                }
            }
        }
    }

    private companion object {
        const val PROFILE_ID = "fixture-profile-id"
    }
}
