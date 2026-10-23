package io.github.excludebarrier.orsikrelay

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.performScrollTo
import io.github.excludebarrier.orsikrelay.ui.home.HomeTags
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
 * Settings open from the gear on Home and "Über die App" from the end of
 * Settings; back returns step by step. Settings itself does not show the
 * app version. The Agents list has no header menu any more.
 */
class SettingsNavigationUiTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun theAgentsListHasNoHeaderMenu() {
        setHost()
        composeRule.waitForHome()
        composeRule.openAgentsTab()
        composeRule.waitForAgents()
        composeRule.onAllNodesWithTag("sessions_overflow_menu").fetchSemanticsNodes().let {
            org.junit.Assert.assertTrue(it.isEmpty())
        }
        composeRule.onNodeWithTag(io.github.excludebarrier.orsikrelay.ui.sessions.SESSIONS_HEADER_TAG)
            .assert(androidx.compose.ui.test.SemanticsMatcher("has the refresh action") { node ->
                node.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsActions.CustomActions) { null }
                    ?.any { it.label == context.getString(R.string.sessions_refresh) } == true
            })
    }

    @Test
    fun theHomeGearOpensSettingsAndBackReturnsHome() {
        setHost()
        composeRule.waitForHome()
        composeRule.onNodeWithTag(HomeTags.SETTINGS).performClick()

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

        composeRule.waitForHome()
        composeRule.onAllNodesWithTag("settings_screen").fetchSemanticsNodes().let {
            org.junit.Assert.assertTrue("settings screen must be gone", it.isEmpty())
        }
    }

    @Test
    fun aboutAtTheEndOfSettingsShowsVersionAndLicenseAndBackReturnsToSettings() {
        setHost()
        composeRule.waitForHome()
        composeRule.onNodeWithTag(HomeTags.SETTINGS).performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("settings_screen").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("settings_about").performScrollTo().performClick()

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

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("settings_screen").fetchSemanticsNodes().isNotEmpty()
        }
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

    private fun waitForText(text: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()
    }
}
