package io.github.excludebarrier.orsikrelay

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import io.github.excludebarrier.orsikrelay.data.session.StartupProfile
import io.github.excludebarrier.orsikrelay.domain.session.HermesSession
import io.github.excludebarrier.orsikrelay.domain.session.SessionListResult
import io.github.excludebarrier.orsikrelay.domain.session.SessionPage
import io.github.excludebarrier.orsikrelay.domain.session.SessionPageSource
import io.github.excludebarrier.orsikrelay.navigation.AppScreen
import io.github.excludebarrier.orsikrelay.navigation.HermesNavHost
import io.github.excludebarrier.orsikrelay.ui.theme.HermesTheme
import org.junit.Rule
import org.junit.Test

/**
 * The settings entry replaces the old appearance switcher in the session
 * list; opening it shows the settings screen and back returns to the list.
 */
class SettingsNavigationUiTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun settingsEntryOpensSettingsAndBackReturnsToSessions() {
        composeRule.setContent {
            HermesTheme {
                HermesNavHost(
                    startDestination = AppScreen.Onboarding,
                    sessionPageSource = SessionPageSource { _, _, _ ->
                        SessionListResult.Success(
                            SessionPage(
                                sessions = listOf(
                                    HermesSession(
                                        id = "fixture-session",
                                        title = "Fixture session",
                                        source = null,
                                        lastActiveEpochSeconds = null,
                                        messageCount = null,
                                        pinned = false
                                    )
                                ),
                                hasMore = false
                            )
                        )
                    },
                    startupProfileResolver = { StartupProfile.Ready("fixture-profile") }
                )
            }
        }

        waitForText(context.getString(R.string.sessions_title))
        composeRule.onNodeWithText(context.getString(R.string.settings_entry)).performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("settings_screen").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("settings_screen").assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.settings_language_section))
            .assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.settings_appearance_section))
            .assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.settings_about_section))
            .assertIsDisplayed()

        composeRule.onNodeWithText(context.getString(R.string.settings_back)).performClick()

        waitForText(context.getString(R.string.sessions_title))
        composeRule.onAllNodesWithTag("settings_screen").fetchSemanticsNodes().let {
            org.junit.Assert.assertTrue("settings screen must be gone", it.isEmpty())
        }
    }

    private fun waitForText(text: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()
    }
}
