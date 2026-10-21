package io.github.excludebarrier.orsikrelay

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import io.github.excludebarrier.orsikrelay.data.session.StartupProfile
import io.github.excludebarrier.orsikrelay.domain.chat.ChatTurnEvent
import io.github.excludebarrier.orsikrelay.domain.chat.StreamingChatSource
import io.github.excludebarrier.orsikrelay.domain.run.RunForegroundServiceStarter
import io.github.excludebarrier.orsikrelay.domain.run.RunSessionController
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
import io.github.excludebarrier.orsikrelay.ui.chat.NoOpNotificationCoordinator
import io.github.excludebarrier.orsikrelay.ui.chat.TAG_CHAT_TITLE
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Navigation test for the live turn slice:
 * the chat screen receives the exact synthetic profile/session/message
 * values through the existing secure route, and back during a running
 * turn cancels the injected synthetic stream before returning to the
 * session list. No network, no real hosts, no credentials.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatTurnNavigationUiTest {
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

    // The navigation host resolves the startup profile on `Dispatchers.IO`
    // and writes its navigation state afterwards. With the default
    // `createAndroidComposeRule()` effect context (UnconfinedTestDispatcher)
    // that continuation - and the recomposition/navigation it drives - resumes
    // on the IO worker, so the composition can be applied off the main thread
    // and a `performClick` issued while that move is in flight can miss the
    // composer's send action. A confined StandardTestDispatcher queues the
    // continuation on the test scheduler, pumped on the main thread by
    // `waitForIdle`/`waitUntil`.
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>(StandardTestDispatcher())

    @Test
    fun submitReceivesExactValuesAndBackDuringTurnKeepsStreamAlive() {
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

        val runController = controllerFor(turnSource)

        composeRule.setContent {
            HermesTheme {
                HermesNavHost(
                    startDestination = AppScreen.Onboarding,
                    sessionPageSource = sessions,
                    messageHistorySource = history,
                    runController = runController,
                    startupProfileResolver = { StartupProfile.Ready(PROFILE_ID) }
                )
            }
        }

        composeRule.waitForHome()
        composeRule.openAgentsTab()
        waitForText("Turn-Session")
        composeRule.onNodeWithText("Turn-Session").performClick()
        waitForText("Fixture-Chatinhalt")

        // Submit through the existing secure route: exact values.
        composeRule
            .onNodeWithText(context.getString(R.string.chat_input_label))
            .performTextInput("Synthese 1")
        ImeClickSettle.settleAfterTyping(composeRule)
        composeRule.onNodeWithContentDescription(context.getString(R.string.chat_send)).performClick()
        assertTrue(turnSource.opened.await(3, TimeUnit.SECONDS))
        assertEquals(PROFILE_ID, turnSource.profileId.get())
        assertEquals(sessionId, turnSource.sessionId.get())
        assertEquals("Synthese 1", turnSource.message.get())

        // Background continuity: back during a running turn does NOT cancel
        // the stream — the run belongs to the app-wide RunSessionController
        // and continues in the background; the navigation simply returns to
        // the session list.
        composeRule.onNodeWithContentDescription(context.getString(R.string.chat_back)).performClick()
        waitForText("Turn-Session")
        assertFalse(
            "the running stream must survive the back navigation",
            turnSource.canceled.await(100, TimeUnit.MILLISECONDS)
        )
        assertEquals("exactly one stream", 1, turnSource.calls.get())
        assertEquals(1, historyCalls.get())
    }

    /**
     * System / gesture back during a running turn. A hardware BACK key (or
     * predictive-back gesture) on the activity is delivered to the
     * [androidx.activity.OnBackPressedDispatcher]; the chat screen's Compose
     * `BackHandler` is registered into that same dispatcher.
     * `ComponentActivity.onBackPressed()` invokes the dispatcher, so this is
     * the faithful instrumentation seam for the system back event (the
     * compose ui-test 1.9.0 resolved here has no `performSystemBack`).
     *
     * The system back must navigate safely back to the session list and
     * leave no lifecycle crash or further UI state behind. The running
     * turn is NOT interrupted — it is owned app-level by the run controller
     * and continues in the background.
     */
    @Test
    fun systemBackDuringRunningTurnKeepsStreamAliveAndReturnsToSessionList() {
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

        val runController = controllerFor(turnSource)

        composeRule.setContent {
            HermesTheme {
                HermesNavHost(
                    startDestination = AppScreen.Onboarding,
                    sessionPageSource = sessions,
                    messageHistorySource = history,
                    runController = runController,
                    startupProfileResolver = { StartupProfile.Ready(PROFILE_ID) }
                )
            }
        }

        composeRule.waitForHome()
        composeRule.openAgentsTab()
        waitForText("SystemBack-Session")
        composeRule.onNodeWithText("SystemBack-Session").performClick()
        // We are now on the chat screen. The title appears before the (empty)
        // history has loaded; wait until the composer accepts input.
        composeRule.onNodeWithTag(TAG_CHAT_TITLE).assertIsDisplayed()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodes(hasSetTextAction() and hasText(context.getString(R.string.chat_input_label)) and isEnabled())
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()

        // Start a turn that stays open (the fake never emits a terminal
        // event and never completes on its own).
        composeRule.onNodeWithText(context.getString(R.string.chat_input_label)).performTextInput("System back")
        ImeClickSettle.settleAfterTyping(composeRule)
        composeRule.onNodeWithContentDescription(context.getString(R.string.chat_send)).performClick()
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

        // The running stream is NOT canceled by the system back — the
        // run continues in the background under the app-level controller.
        assertFalse(
            "the running stream must survive the system back",
            turnSource.canceled.await(100, TimeUnit.MILLISECONDS)
        )
        // The activity is still usable (not crashed / not finished).
        assertTrue("activity must still be usable after system back", !composeRule.activity.isFinishing)
        assertEquals("exactly one stream", 1, turnSource.calls.get())
    }

    /**
     * A double back event on the chat screen (no active turn) — as from a
     * fast double-tap on the back button or a spurious input event — must NOT pop
     * the navigation stack empty. The first back returns to the session
     * list; the second back reaches the single Home root and must stop
     * there. Without the guard the NavHost is left without a destination,
     * renders an empty (white) screen whose BackHandler is gone, and the next
     * back closes the whole task.
     */
    @Test
    fun doubleBackWithoutActiveTurnKeepsHomeVisible() {
        val turnSource = FakeTurnSource()
        val sessionId = "fixture/double-back-session"
        val sessions = SessionPageSource { _, _, _ ->
            SessionListResult.Success(
                SessionPage(
                    sessions = listOf(
                        HermesSession(
                            id = sessionId,
                            title = "DoubleBack-Session",
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
        val runController = controllerFor(turnSource)

        composeRule.setContent {
            HermesTheme {
                HermesNavHost(
                    startDestination = AppScreen.Onboarding,
                    sessionPageSource = sessions,
                    messageHistorySource = history,
                    runController = runController,
                    startupProfileResolver = { StartupProfile.Ready(PROFILE_ID) }
                )
            }
        }

        composeRule.waitForHome()
        composeRule.openAgentsTab()
        waitForText("DoubleBack-Session")
        composeRule.onNodeWithText("DoubleBack-Session").performClick()
        composeRule.onNodeWithTag(TAG_CHAT_TITLE).assertIsDisplayed()

        // No turn is active: both backs go straight to onBack().
        composeRule.runOnIdle {
            composeRule.activity.onBackPressed()
            composeRule.activity.onBackPressed()
        }

        // The double back must stop at the single Home root (NOT an empty
        // white screen) and must not finish the activity.
        composeRule.waitForHome()
        assertTrue(
            "activity must not be finished after the double back",
            !composeRule.activity.isFinishing
        )
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
