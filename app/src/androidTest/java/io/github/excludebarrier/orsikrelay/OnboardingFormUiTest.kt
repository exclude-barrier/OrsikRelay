package io.github.excludebarrier.orsikrelay

import android.content.Context
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose instrumentation tests for the Mission 002A onboarding form:
 *
 * 1. Submitting an empty form shows the field-specific validation
 *    errors and stays on the Onboarding screen.
 * 2. Valid synthetic values navigate to the Sessions screen.
 * 3. The API key is never rendered as plain text anywhere in the UI.
 *
 * All values are synthetic fixtures; no network, no real hosts, no
 * real credentials.
 */
@RunWith(AndroidJUnit4::class)
class OnboardingFormUiTest {

    /** Synthetic fixture key; not a real credential. */
    private val syntheticApiKey = "sk-ui-test-0123456789"

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private fun clickSubmit() {
        composeRule.onNodeWithText("Verbindung vorbereiten").performClick()
    }

    private fun hideSoftwareKeyboard() {
        composeRule.runOnUiThread {
            val inputMethodManager = composeRule.activity
                .getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            inputMethodManager.hideSoftInputFromWindow(
                composeRule.activity.window.decorView.windowToken,
                0
            )
            composeRule.activity.currentFocus?.clearFocus()
        }
        composeRule.waitForIdle()
    }

    private fun assertNoNodeWithText(text: String) {
        val matches = composeRule.onAllNodes(hasText(text)).fetchSemanticsNodes()
        assertTrue("Expected no matching UI node", matches.isEmpty())
    }

    /**
     * Verifies, while the Onboarding form is on screen: the API-key field
     * is marked as password (no plain text) and the raw key occurs in no
     * accessibility description.
     */
    private fun assertApiKeyFieldIsSecureOnOnboarding() {
        val node = composeRule
            .onNodeWithContentDescription("API-Key", useUnmergedTree = true)
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

    /**
     * Verifies the raw key is not rendered as plain text anywhere in the
     * UI. Only the rendered [SemanticsProperties.Text] values are checked:
     * the editable text of a (password) field is part of the accessibility
     * representation and is covered by the password assertion instead.
     */
    private fun assertRawKeyNotRenderedAsPlainText() {
        val anyNode = SemanticsMatcher("any node") { true }
        val renderedTexts = composeRule
            .onAllNodes(anyNode, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .flatMap { node ->
                if (SemanticsProperties.Text in node.config) {
                    node.config[SemanticsProperties.Text]
                        .map { annotated -> annotated.toString() }
                } else {
                    emptyList()
                }
            }
        assertFalse(
            "API key must not be rendered as plain text",
            renderedTexts.any { it.contains(syntheticApiKey) }
        )
    }

    @Test
    fun submittingEmptyFormShowsFieldErrorsAndStaysOnOnboarding() {
        // Startscreen mit Titel.
        composeRule.onNodeWithText("Hermes einrichten").assertIsDisplayed()

        clickSubmit()

        // Feldbezogene Validierungsfehler werden angezeigt.
        composeRule
            .onNodeWithText("Profilname darf nicht leer sein.")
            .assertIsDisplayed()
        composeRule
            .onNodeWithText("Bitte eine absolute HTTPS-URL mit Host eingeben.")
            .assertIsDisplayed()
        composeRule.onNodeWithText("API-Key darf nicht leer sein.").assertIsDisplayed()

        // Die App bleibt auf Onboarding und navigiert nicht zu Sessions.
        composeRule.onNodeWithText("Hermes einrichten").assertIsDisplayed()
        assertNoNodeWithText("Sessions")
    }

    @Test
    fun validSyntheticValuesNavigateToSessions() {
        composeRule.onNodeWithText("Hermes einrichten").assertIsDisplayed()
        hideSoftwareKeyboard()

        composeRule
            .onNodeWithContentDescription("API-Key", useUnmergedTree = true)
            .performTextInput(syntheticApiKey)
        // Passwort-Semantics und Semantics-Leakage pruefen, solange das
        // Formular sichtbar ist (nach der Navigation existiert es nicht mehr).
        assertApiKeyFieldIsSecureOnOnboarding()

        composeRule
            .onNodeWithTag("onboardingProfileName")
            .performScrollTo()
            .performTextInput("Android")
        composeRule
            .onNodeWithTag("onboardingBaseUrl")
            .performScrollTo()
            .performTextInput("https://example.org/p/android")

        clickSubmit()

        composeRule.onNodeWithText("Sessions").assertIsDisplayed()
        // Der Rohwert wird auch nach der Navigation nirgends als
        // gerendeter Text im UI angezeigt.
        assertRawKeyNotRenderedAsPlainText()
    }

    @Test
    fun apiKeyIsHiddenInTheFieldAndNotShownAsPlainText() {
        composeRule.onNodeWithText("Hermes einrichten").assertIsDisplayed()

        composeRule
            .onNodeWithContentDescription("API-Key", useUnmergedTree = true)
            .performTextInput(syntheticApiKey)

        // Das Feld selbst ist ein Passwortfeld: kein Klartext und keine
        // Semantics-Leakage über sämtliche Compose-Knoten hinweg.
        assertApiKeyFieldIsSecureOnOnboarding()
        assertRawKeyNotRenderedAsPlainText()

        // Bleibt auf Onboarding, solange nicht gültig abgeschickt wird.
        assertNoNodeWithText("Sessions")
    }
}
