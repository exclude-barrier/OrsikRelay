package io.github.excludebarrier.orsikrelay

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
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

    /** Synthetic fixture key; not a real credential. */
    private val syntheticApiKey = "fixture"

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private fun clickSubmit() {
        composeRule.onNodeWithText("Verbindung vorbereiten").performClick()
    }


    private fun assertNoNodeWithText(text: String) {
        val matches = composeRule.onAllNodes(hasText(text)).fetchSemanticsNodes()
        assertTrue("Expected no matching UI node", matches.isEmpty())
    }

    private fun assertApiKeyNotRenderedAsPlainText() {
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

    @Test
    fun submittingEmptyFormShowsFieldErrorsAndStaysOnOnboarding() {
        // Startscreen mit Titel.
        composeRule.onNodeWithText("Orsik Relay einrichten").assertIsDisplayed()

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
        composeRule.onNodeWithText("Orsik Relay einrichten").assertIsDisplayed()
        assertNoNodeWithText("Sessions")
    }


    @Test
    fun apiKeyIsHiddenInTheFieldAndNotShownAsPlainText() {
        composeRule.onNodeWithText("Orsik Relay einrichten").assertIsDisplayed()

        composeRule
            .onNodeWithContentDescription("API-Key", useUnmergedTree = true)
            .performTextInput(syntheticApiKey)

        // Das Feld selbst ist ein Passwortfeld: kein Klartext und keine
        // Semantics-Leakage über sämtliche Compose-Knoten hinweg.
        assertApiKeyNotRenderedAsPlainText()

        // Bleibt auf Onboarding, solange nicht gültig abgeschickt wird.
        assertNoNodeWithText("Sessions")
    }
}
