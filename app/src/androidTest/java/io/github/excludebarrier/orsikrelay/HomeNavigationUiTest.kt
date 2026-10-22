package io.github.excludebarrier.orsikrelay

import androidx.compose.ui.test.assertCountEquals
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.Espresso
import io.github.excludebarrier.orsikrelay.data.session.StartupProfile
import io.github.excludebarrier.orsikrelay.domain.chat.ChatTurnEvent
import io.github.excludebarrier.orsikrelay.domain.chat.StreamingChatSource
import io.github.excludebarrier.orsikrelay.domain.connection.HermesConnectionResult
import io.github.excludebarrier.orsikrelay.domain.home.ServerConnectionCheck
import io.github.excludebarrier.orsikrelay.domain.message.MessageHistoryResult
import io.github.excludebarrier.orsikrelay.domain.message.MessageHistorySource
import io.github.excludebarrier.orsikrelay.domain.message.MessagePage
import io.github.excludebarrier.orsikrelay.domain.profile.ProfileAdmin
import io.github.excludebarrier.orsikrelay.domain.profile.ProfileCredentialKind
import io.github.excludebarrier.orsikrelay.domain.profile.ServerProfile
import io.github.excludebarrier.orsikrelay.domain.run.RunForegroundServiceStarter
import io.github.excludebarrier.orsikrelay.domain.run.RunServerStatus
import io.github.excludebarrier.orsikrelay.domain.run.RunSessionController
import io.github.excludebarrier.orsikrelay.domain.run.RunStatusRequester
import io.github.excludebarrier.orsikrelay.domain.run.RunStatusResult
import io.github.excludebarrier.orsikrelay.domain.session.HermesSession
import io.github.excludebarrier.orsikrelay.domain.session.SessionCreateResult
import io.github.excludebarrier.orsikrelay.domain.session.SessionCreator
import io.github.excludebarrier.orsikrelay.domain.session.SessionListResult
import io.github.excludebarrier.orsikrelay.domain.session.SessionPage
import io.github.excludebarrier.orsikrelay.domain.session.SessionPageSource
import io.github.excludebarrier.orsikrelay.navigation.ACTIVITY_PLACEHOLDER_TAG
import io.github.excludebarrier.orsikrelay.navigation.AppScreen
import io.github.excludebarrier.orsikrelay.navigation.HermesNavHost
import io.github.excludebarrier.orsikrelay.ui.profiles.PROFILES_SCREEN_TAG
import io.github.excludebarrier.orsikrelay.navigation.bottomTabTag
import io.github.excludebarrier.orsikrelay.ui.chat.NoOpNotificationCoordinator
import io.github.excludebarrier.orsikrelay.ui.home.HomeTags
import io.github.excludebarrier.orsikrelay.ui.theme.HermesTheme
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Home as the start page, its tabs and the approval card's way into the chat. */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeNavigationUiTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    // Same reason as SessionChatNavigationUiTest: startup resolution runs on
    // Dispatchers.IO; a confined dispatcher keeps navigation on the main thread.
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>(StandardTestDispatcher())

    private val events = Channel<ChatTurnEvent>(Channel.UNLIMITED)
    private val streamedTurn = AtomicReference<Pair<String, String>>()
    private val source = object : StreamingChatSource {
        override fun streamTurn(profileId: String, sessionId: String, message: String): Flow<ChatTurnEvent> {
            streamedTurn.set(sessionId to message)
            return events.receiveAsFlow()
        }
    }
    private val createdSessions = AtomicInteger()
    private val creator = SessionCreator { _, _ ->
        createdSessions.incrementAndGet()
        SessionCreateResult.Success(NEW_SESSION_ID, null)
    }
    private val controller = RunSessionController(
        chatSource = source,
        serviceStarter = object : RunForegroundServiceStarter {
            override fun start(profileId: String, sessionId: String) {}
            override fun stop() {}
        },
        notificationCoordinator = NoOpNotificationCoordinator
    )
    private val admin = object : ProfileAdmin {
        override fun listProfiles() = listOf(ServerProfile.create(PROFILE_ID, "hp-server", "https://hp.local"))
        override fun deleteProfile(profileId: String) = Unit
        override fun selectActiveServer(profileId: String) = Unit
        override fun credentialKind(profileId: String) = ProfileCredentialKind.API_KEY
    }
    private val sessions = SessionPageSource { _, _, _ ->
        SessionListResult.Success(
            SessionPage(listOf(HermesSession(SESSION_ID, "Docker updates", null, null, 2, false)), hasMore = false)
        )
    }
    private val openedSession = AtomicReference<String>()
    private val history = MessageHistorySource { _, sessionId ->
        openedSession.set(sessionId)
        MessageHistoryResult.Success(MessagePage(emptyList()))
    }
    private val connected = ServerConnectionCheck {
        HermesConnectionResult.Success(version = "test", sessionChatInteractiveApprovals = true)
    }

    private fun statusOf(status: RunServerStatus) = object : RunStatusRequester {
        override suspend fun getRunStatus(profileId: String, runId: String) = RunStatusResult.Known(status)
    }

    private fun show(status: RunStatusRequester = statusOf(RunServerStatus.RUNNING)) {
        composeRule.setContent {
            HermesTheme {
                HermesNavHost(
                    startDestination = AppScreen.Onboarding,
                    sessionPageSource = sessions,
                    messageHistorySource = history,
                    runController = controller,
                    runStatusRequester = status,
                    serverConnectionCheck = connected,
                    sessionCreator = creator,
                    profileAdmin = admin,
                    startupProfileResolver = { StartupProfile.Ready(PROFILE_ID) }
                )
            }
        }
        waitForTag(HomeTags.SCREEN)
    }

    @Test
    fun startsOnHomeAndTheTabsSwitchAndReturn() {
        show()
        waitForText("hp-server")
        composeRule.onNodeWithTag(bottomTabTag(AppScreen.Home)).assertIsSelected()

        composeRule.onNodeWithTag(bottomTabTag(AppScreen.Sessions)).performClick()
        waitForText("Docker updates")
        // The Agents tab keeps the navigation bar, with its own tab selected.
        composeRule.onNodeWithTag(bottomTabTag(AppScreen.Sessions)).assertIsDisplayed().assertIsSelected()
        composeRule.onNodeWithTag(bottomTabTag(AppScreen.Server)).performClick()
        waitForTag(PROFILES_SCREEN_TAG)
        // The Server tab is the server management, with the bar still shown.
        composeRule.onNodeWithTag(bottomTabTag(AppScreen.Server)).assertIsDisplayed().assertIsSelected()
        composeRule.onAllNodesWithTag("settings_back").assertCountEquals(0)
        composeRule.onNodeWithTag(bottomTabTag(AppScreen.Activity)).performClick()
        waitForTag(ACTIVITY_PLACEHOLDER_TAG)

        // Back from any tab returns to Home.
        Espresso.pressBack()
        waitForTag(HomeTags.SCREEN)
        composeRule.onNodeWithTag(bottomTabTag(AppScreen.Home)).assertIsSelected()
    }

    @Test
    fun aPolledApprovalShowsTheCardAndReviewOpensThatChat() {
        show(status = statusOf(RunServerStatus.WAITING_FOR_APPROVAL))
        composeRule.runOnIdle {
            controller.startTurn(PROFILE_ID, SESSION_ID, "Docker updates")
            events.trySend(ChatTurnEvent.RunStarted("run-1"))
        }

        waitForTag(HomeTags.APPROVAL)
        composeRule.onNodeWithTag(HomeTags.APPROVAL).assertIsDisplayed()
        composeRule.onNodeWithTag(HomeTags.APPROVAL_REVIEW).performClick()

        composeRule.waitUntil(5_000) { openedSession.get() == SESSION_ID }
        assertEquals(SESSION_ID, openedSession.get())
        controller.close()
    }

    @Test
    fun sendingFromHomeCreatesOneSessionStartsTheTurnAndOpensItsChat() {
        show()
        waitForText(context.getString(R.string.home_status_connected))

        composeRule.onNodeWithTag(HomeTags.COMPOSER_INPUT).performTextInput("Check disk space")
        composeRule.onNodeWithTag(HomeTags.COMPOSER_SEND).performClick()

        composeRule.waitUntil(5_000) { openedSession.get() == NEW_SESSION_ID }
        assertEquals(1, createdSessions.get())
        assertEquals(NEW_SESSION_ID to "Check disk space", streamedTurn.get())
        controller.close()
    }

    private fun waitForTag(tag: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()
    }

    private fun waitForText(text: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()
    }

    private companion object {
        const val PROFILE_ID = "fixture-profile-id"
        const val SESSION_ID = "session-approval"
        const val NEW_SESSION_ID = "session-new"
    }
}
