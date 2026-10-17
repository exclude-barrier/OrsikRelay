package io.github.excludebarrier.orsikrelay

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.excludebarrier.orsikrelay.ui.onboarding.OnboardingConnectionState
import io.github.excludebarrier.orsikrelay.ui.onboarding.OnboardingFailureReason
import io.github.excludebarrier.orsikrelay.ui.onboarding.OnboardingScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The action hint below the unchanged failure message.
 *
 * Hints appear only for reasons with a useful next action; the closed
 * error message stays visible above them.
 */
@RunWith(AndroidJUnit4::class)
class OnboardingFailureHintUiTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @get:Rule
    val composeRule = createComposeRule()

    private var state by mutableStateOf<OnboardingConnectionState>(OnboardingConnectionState.Idle)

    @Test
    fun actionableFailuresShowTheirHintAboveTheSubmitButton() {
        setOnboarding()

        val cases = listOf(
            OnboardingFailureReason.TLS to R.string.onboarding_hint_tls,
            OnboardingFailureReason.INCOMPATIBLE_API to R.string.onboarding_hint_incompatible_api,
            OnboardingFailureReason.NETWORK to R.string.onboarding_hint_network,
            OnboardingFailureReason.PAIRING_CODE_INVALID to R.string.onboarding_hint_pairing_code_invalid
        )
        cases.forEach { (reason, hint) ->
            composeRule.runOnIdle {
                state = OnboardingConnectionState.Failure(reason)
            }
            composeRule.onNodeWithTag("onboardingConnectionError").assertIsDisplayed()
            composeRule.onNodeWithTag("onboardingConnectionHint").assertIsDisplayed()
            composeRule.onNodeWithText(context.getString(hint)).assertIsDisplayed()
        }
    }

    @Test
    fun failuresWithoutAnActionShowNoHintRow() {
        setOnboarding()

        listOf(
            OnboardingFailureReason.HTTP,
            OnboardingFailureReason.INVALID_INPUT,
            OnboardingFailureReason.PAIRING_RATE_LIMITED
        ).forEach { reason ->
            composeRule.runOnIdle {
                state = OnboardingConnectionState.Failure(reason)
            }
            composeRule.onNodeWithTag("onboardingConnectionError").assertIsDisplayed()
            composeRule.onNodeWithTag("onboardingConnectionHint").assertDoesNotExist()
        }
    }

    private fun setOnboarding() {
        composeRule.setContent {
            OnboardingScreen(
                onValidDraft = {},
                onValidPairing = { _, _, _ -> },
                connectionState = state
            )
        }
    }
}
