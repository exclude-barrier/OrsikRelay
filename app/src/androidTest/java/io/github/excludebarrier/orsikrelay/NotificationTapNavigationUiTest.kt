package io.github.excludebarrier.orsikrelay

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import io.github.excludebarrier.orsikrelay.data.session.StartupProfile
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
import io.github.excludebarrier.orsikrelay.navigation.NotificationTap
import io.github.excludebarrier.orsikrelay.ui.theme.HermesTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Rule
import org.junit.Test

/**
 * A notification tap is a navigation request, not a one-time bootstrap: a
 * second tap on the same (profileId, sessionId) after the user returned to the
 * session list must open that chat again. The monotonically increasing
 * delivery number makes each tap a distinct input value.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationTapNavigationUiTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>(StandardTestDispatcher())

    @Test
    fun repeatedTapOnTheSameSessionNavigatesAgain() {
        var tap by mutableStateOf(
            NotificationTap(profileId = PROFILE_ID, sessionId = SESSION_ID, delivery = 1L)
        )
        composeRule.setContent {
            HermesTheme {
                HermesNavHost(
                    startDestination = AppScreen.Onboarding,
                    sessionPageSource = sessions,
                    messageHistorySource = history,
                    startupProfileResolver = { StartupProfile.Ready(PROFILE_ID) },
                    notificationBootstrap = tap
                )
            }
        }

        waitForText(CHAT_CONTENT)
        composeRule.onNodeWithContentDescription(context.getString(R.string.chat_back))
            .performClick()
        composeRule.waitForHome()
        composeRule.openAgentsTab()
        waitForText(SESSION_TITLE)

        composeRule.runOnIdle {
            tap = tap.copy(delivery = tap.delivery + 1L)
        }

        waitForText(CHAT_CONTENT)
    }

    private val sessions = SessionPageSource { _, _, _ ->
        SessionListResult.Success(
            SessionPage(
                sessions = listOf(
                    HermesSession(
                        id = SESSION_ID,
                        title = SESSION_TITLE,
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

    private val history = MessageHistorySource { _, _ ->
        MessageHistoryResult.Success(
            MessagePage(
                listOf(
                    HermesChatMessage(
                        id = 1,
                        role = MessageRole.ASSISTANT,
                        content = CHAT_CONTENT,
                        timestampSeconds = null,
                        toolName = null
                    )
                )
            )
        )
    }

    private fun waitForText(text: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()
    }

    private companion object {
        const val PROFILE_ID = "fixture-profile-id"
        const val SESSION_ID = "fixture-session-id"
        const val SESSION_TITLE = "Fixture-Session"
        const val CHAT_CONTENT = "Fixture-Chatinhalt"
    }
}
