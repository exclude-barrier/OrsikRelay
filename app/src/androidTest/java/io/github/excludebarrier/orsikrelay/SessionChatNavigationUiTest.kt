package io.github.excludebarrier.orsikrelay

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import io.github.excludebarrier.orsikrelay.ui.theme.HermesTheme
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SessionChatNavigationUiTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun sessionTapLoadsExactIdAndBackReturnsToList() {
        val receivedProfile = AtomicReference<String>()
        val receivedSession = AtomicReference<String>()
        val sessionId = "fixture/a?b#c d&ü"
        val sessions = SessionPageSource { _, _, _ ->
            SessionListResult.Success(
                SessionPage(
                    sessions = listOf(
                        HermesSession(
                            id = sessionId,
                            title = "Fixture-Session",
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
                    startupProfileResolver = { StartupProfile.Ready(PROFILE_ID) }
                )
            }
        }

        waitForText("Fixture-Session")
        composeRule.onNodeWithText("Fixture-Session").performClick()
        waitForText("Fixture-Chatinhalt")
        assertEquals(PROFILE_ID, receivedProfile.get())
        assertEquals(sessionId, receivedSession.get())

        composeRule.onNodeWithText("Zurück").performClick()
        waitForText("Fixture-Session")
    }

    private fun waitForText(text: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()
    }

    private companion object {
        const val PROFILE_ID = "fixture-profile-id"
    }
}
