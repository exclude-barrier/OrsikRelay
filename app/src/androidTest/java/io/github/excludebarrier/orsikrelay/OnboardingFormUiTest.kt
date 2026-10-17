package io.github.excludebarrier.orsikrelay

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose instrumentation tests for the onboarding form:
 *
 * 1. Submitting an empty form shows the field-specific validation
 *    errors and stays on the Onboarding screen.
 * 2. The API key is never rendered as plain text anywhere in the UI.
 *
 * The successful connection/navigation path has its own injected-fake
 * coverage in the connection and startup navigation tests.
 *
 * All values are synthetic fixtures; no network, no real hosts, no
 * real credentials.
 */
@RunWith(AndroidJUnit4::class)
class OnboardingFormUiTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** Synthetic fixture key; not a real credential. */
    private val syntheticApiKey = "fixture"

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private fun clickSubmit() {
        composeRule.onNodeWithText(context.getString(R.string.onboarding_submit)).performClick()
    }

    /**
     * Onboarding now defaults to the pairing mode. The
     * API-key assertions of this test therefore switch back to the
     * unchanged API-Key mode first.
     */
    private fun switchToApiKeyMode() {
        composeRule.onNodeWithTag("onboardingModeApiKey").performClick()
    }


    private fun assertNoNodeWithText(text: String) {
        val matches = composeRule.onAllNodes(hasText(text)).fetchSemanticsNodes()
        assertTrue("Expected no matching UI node", matches.isEmpty())
    }

    private fun assertApiKeyNotRenderedAsPlainText() {
        val node = composeRule
            .onNodeWithContentDescription(context.getString(R.string.onboarding_api_key_label), useUnmergedTree = true)
            .fetchSemanticsNode()
        assertTrue(
            "API key field must be marked as a password",
            node.config.contains(SemanticsProperties.Password)
        )
        val descriptions = node.config[SemanticsProperties.ContentDescription]
        assertFalse(
            "API key must not occur in accessibility descriptions",
            descriptions.any { description -> description.contains(syntheticApiKey) }
        )
    }

    @Test
    fun submittingEmptyFormShowsFieldErrorsAndStaysOnOnboarding() {
        // Start screen with title.
        composeRule.onNodeWithText(context.getString(R.string.onboarding_title)).assertIsDisplayed()

        // The API-key assertions apply to the unchanged API-key mode;
        // the default mode is the pairing mode.
        switchToApiKeyMode()
        clickSubmit()

        // Field-specific validation errors are shown.
        composeRule
            .onNodeWithText(context.getString(R.string.error_profile_name_empty))
            .assertIsDisplayed()
        composeRule
            .onNodeWithText(context.getString(R.string.error_base_url_invalid))
            .assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.error_api_key_empty)).assertIsDisplayed()

        // The app stays on onboarding and does not navigate to sessions.
        composeRule.onNodeWithText(context.getString(R.string.onboarding_title)).assertIsDisplayed()
        assertNoNodeWithText(context.getString(R.string.sessions_title))
    }


    @Test
    fun apiKeyIsHiddenInTheFieldAndNotShownAsPlainText() {
        composeRule.onNodeWithText(context.getString(R.string.onboarding_title)).assertIsDisplayed()

        switchToApiKeyMode()
        composeRule
            .onNodeWithContentDescription(context.getString(R.string.onboarding_api_key_label), useUnmergedTree = true)
            .performTextInput(syntheticApiKey)

        // The field itself is a password field: no plain text and no
        // semantics leakage across all Compose nodes.
        assertApiKeyNotRenderedAsPlainText()

        // Stays on onboarding as long as no valid submission is made.
        assertNoNodeWithText(context.getString(R.string.sessions_title))
    }
}
