package io.github.excludebarrier.orsikrelay

import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import io.github.excludebarrier.orsikrelay.data.session.StartupProfile
import io.github.excludebarrier.orsikrelay.domain.message.HermesChatMessage
import io.github.excludebarrier.orsikrelay.domain.message.MessageHistoryResult
import io.github.excludebarrier.orsikrelay.domain.message.MessageHistorySource
import io.github.excludebarrier.orsikrelay.domain.message.MessagePage
import io.github.excludebarrier.orsikrelay.domain.message.MessageRole
import io.github.excludebarrier.orsikrelay.domain.session.SessionCreateResult
import io.github.excludebarrier.orsikrelay.domain.session.SessionCreator
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

/**
 * Navigation test for the new-session flow:
 * a successful synthetic creation opens the existing read-only chat of
 * the returned synthetic session ID; special characters are safely
 * encoded through the existing route.
 */
class CreateSessionNavigationUiTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun successfulCreateOpensChatOfReturnedIdWithSafeEncoding() {
        val receivedSession = AtomicReference<String>()
        val navController = AtomicReference<NavHostController>()
        val specialId = "neu?e/sess&ion ü"

        composeRule.setContent {
            val controller = rememberNavController()
            navController.set(controller)
            HermesTheme {
                HermesNavHost(
                    startDestination = AppScreen.Onboarding,
                    navController = controller,
                    sessionPageSource = emptySessions,
                    messageHistorySource = MessageHistorySource { profileId, sessionId ->
                        receivedSession.set(sessionId)
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
                    },
                    sessionCreator = SessionCreator { _, title ->
                        SessionCreateResult.Success(specialId, title)
                    },
                    startupProfileResolver = { StartupProfile.Ready(PROFILE_ID) }
                )
            }
        }

        // The FAB is visible and opens the dialog.
        composeRule
            .onNodeWithContentDescription(context.getString(R.string.new_session_fab))
            .performClick()
        composeRule.onNodeWithText(context.getString(R.string.new_session_title)).assertIsDisplayed()

        composeRule
            .onNodeWithText(context.getString(R.string.new_session_title_label))
            .performTextInput("Synthetischer Titel")
        composeRule.onNodeWithText(context.getString(R.string.new_session_create)).performClick()

        // The new session opens the existing chat of the synthetic ID.
        waitForText("Fixture-Chatinhalt")
        assertEquals(specialId, receivedSession.get())
        composeRule.waitUntil(timeoutMillis = 5_000) {
            navController.get().currentBackStackEntry?.lifecycle?.currentState
                ?.isAtLeast(Lifecycle.State.CREATED) == true
        }
        composeRule.waitForIdle()
        // Back is already covered without the additional dialog/navigation
        // race in the existing SessionChatNavigationUiTest.
    }

    private val emptySessions = SessionPageSource { _, _, _ ->
        SessionListResult.Success(
            SessionPage(
                sessions = emptyList(),
                hasMore = false
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
    }
}
