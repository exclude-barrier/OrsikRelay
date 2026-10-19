package io.github.excludebarrier.orsikrelay

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
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
 * The settings and about entries live in the session list's overflow menu;
 * each opens its screen and back returns to the list. Settings itself no
 * longer shows the app version.
 */
class SettingsNavigationUiTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun settingsEntryOpensSettingsAndBackReturnsToSessions() {
        setHost()
        waitForText(context.getString(R.string.sessions_title))
        openOverflowMenu()
        composeRule.onNodeWithText(context.getString(R.string.settings_entry)).performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("settings_screen").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("settings_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("settings_language_box").assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.settings_appearance_section))
            .assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.settings_chat_section))
            .assertExists()
        composeRule.onNodeWithTag("settings_notifications").assertExists()
        composeRule.onNodeWithText(context.getString(R.string.settings_privacy_section))
            .assertExists()
        composeRule.onNodeWithText(context.getString(R.string.settings_version_label))
            .assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(R.string.settings_license_label))
            .assertDoesNotExist()

        composeRule.onNodeWithContentDescription(context.getString(R.string.settings_back)).performClick()

        waitForText(context.getString(R.string.sessions_title))
        composeRule.onAllNodesWithTag("settings_screen").fetchSemanticsNodes().let {
            org.junit.Assert.assertTrue("settings screen must be gone", it.isEmpty())
        }
    }

    @Test
    fun aboutEntryOpensAboutWithVersionAndLicenseAndBackReturnsToSessions() {
        setHost()
        waitForText(context.getString(R.string.sessions_title))
        openOverflowMenu()
        composeRule.onNodeWithText(context.getString(R.string.settings_about_section)).performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("about_screen").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("about_screen").assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.settings_version_label))
            .assertIsDisplayed()
        composeRule.onNodeWithText(BuildConfig.VERSION_NAME).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.settings_license_label))
            .assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.settings_license_value))
            .assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.settings_source_label))
            .assertIsDisplayed()

        composeRule.onNodeWithContentDescription(context.getString(R.string.settings_back)).performClick()

        waitForText(context.getString(R.string.sessions_title))
        composeRule.onAllNodesWithTag("about_screen").fetchSemanticsNodes().let {
            org.junit.Assert.assertTrue("about screen must be gone", it.isEmpty())
        }
    }

    private fun setHost() {
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
    }

    private fun openOverflowMenu() {
        composeRule.onNodeWithContentDescription(
            context.getString(R.string.sessions_more_options)
        ).performClick()
        composeRule.waitForIdle()
    }

    private fun waitForText(text: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()
    }
}
