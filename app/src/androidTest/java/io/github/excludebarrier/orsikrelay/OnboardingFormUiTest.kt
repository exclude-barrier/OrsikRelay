package io.github.excludebarrier.orsikrelay

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick

import androidx.compose.ui.test.performTextInput
import io.github.excludebarrier.orsikrelay.domain.profile.ProfileDraft
import io.github.excludebarrier.orsikrelay.ui.onboarding.OnboardingConnectionState
import io.github.excludebarrier.orsikrelay.ui.onboarding.OnboardingFailureReason
import io.github.excludebarrier.orsikrelay.ui.onboarding.OnboardingScreen
import io.github.excludebarrier.orsikrelay.ui.theme.HermesTheme

import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class OnboardingFormUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun emptySubmitShowsLocalErrorsAndDoesNotStartConnection() {
        var submitted = 0
        setScreen(onDraft = { submitted += 1 })

        composeRule.onNodeWithTag("onboardingSubmitButton").performClick()

        composeRule.onNodeWithText("Profilname darf nicht leer sein.").assertIsDisplayed()
        composeRule.onNodeWithText(
            "Bitte eine absolute HTTPS-URL mit Host eingeben."
        ).assertIsDisplayed()
        composeRule.onNodeWithText("API-Key darf nicht leer sein.").assertIsDisplayed()
        assertTrue(submitted == 0)
    }

    @Test
    fun apiKeyRemainsPasswordSemanticsAndIsNotRenderedAsText() {
        setScreen()

        composeRule.onNodeWithContentDescription("API-Key", useUnmergedTree = true)
            .performTextInput(FIXTURE_KEY)

        val secretNode = composeRule
            .onNodeWithContentDescription("API-Key", useUnmergedTree = true)
            .fetchSemanticsNode()
        assertTrue(secretNode.config.contains(SemanticsProperties.Password))
        val renderedTexts = composeRule
            .onAllNodes(SemanticsMatcher("any node") { true }, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .flatMap { node ->
                if (SemanticsProperties.Text in node.config) {
                    node.config[SemanticsProperties.Text].map { it.toString() }
                } else {
                    emptyList()
                }
            }
        assertTrue(renderedTexts.none { it.contains(FIXTURE_KEY) })
    }

    @Test
    fun connectingDisablesInputsAndSubmitAndShowsProgressText() {
        setScreen(connectionState = OnboardingConnectionState.Connecting)

        composeRule.onNodeWithTag("onboardingProfileName").assertIsNotEnabled()
        composeRule.onNodeWithTag("onboardingBaseUrl").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("API-Key", useUnmergedTree = true)
            .assertIsNotEnabled()
        composeRule.onNodeWithTag("onboardingSubmitButton").assertIsNotEnabled()
        composeRule.onNodeWithText("Verbindung wird geprüft…").assertIsDisplayed()
    }

    @Test
    fun connectionFailureShowsGenericMessageAndInputChangeRequestsReset() {
        var resetRequested = false
        setScreen(
            connectionState = OnboardingConnectionState.Failure(
                OnboardingFailureReason.INVALID_CREDENTIALS
            ),
            onInputChanged = { resetRequested = true }
        )

        composeRule.onNodeWithText("Der API-Key wurde abgelehnt.").assertIsDisplayed()
        composeRule.onNodeWithTag("onboardingProfileName").performTextInput("F")

        assertTrue(resetRequested)
    }

    private fun setScreen(
        connectionState: OnboardingConnectionState = OnboardingConnectionState.Idle,
        onDraft: (ProfileDraft) -> Unit = {},
        onInputChanged: () -> Unit = {}
    ) {
        composeRule.setContent {
            HermesTheme {
                OnboardingScreen(
                    onValidDraft = onDraft,
                    connectionState = connectionState,
                    onInputChanged = onInputChanged
                )
            }
        }
    }

    private companion object {
        const val FIXTURE_KEY = "fixture-credential"
    }
}
