package io.github.excludebarrier.orsikrelay

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import io.github.excludebarrier.orsikrelay.ui.onboarding.OnboardingScreen
import io.github.excludebarrier.orsikrelay.ui.theme.HermesTheme
import org.junit.Rule
import org.junit.Test

/** A pairing link that arrives while the form is open is an offer, not a silent edit. */
class OnboardingLinkOfferUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private var link by mutableStateOf(Triple("", "", ""))

    private fun show() {
        composeRule.setContent {
            HermesTheme {
                OnboardingScreen(
                    onValidDraft = {},
                    onValidPairing = { _, _, _ -> },
                    initialProfileName = link.first,
                    initialBaseUrl = link.second,
                    initialPairingCode = link.third
                )
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun anUntouchedFormTakesALaterLinkDirectly() {
        show()
        link = Triple("Heim", "https://heim.example", "ABCDEFGHJKMNPQRSTVWXYZ12")
        composeRule.waitForIdle()

        composeRule.onAllNodesWithTag("onboardingLinkOfferApply").assertCountEquals(0)
        composeRule.onNodeWithTag("onboardingBaseUrl").assertTextContains("https://heim.example")
    }

    @Test
    fun aFilledFormAsksFirstAndDiscardKeepsTheUsersServer() {
        show()
        composeRule.onNodeWithTag("onboardingBaseUrl").performTextInput("https://mine.example")
        link = Triple("Evil", "https://attacker.example", "ABCDEFGHJKMNPQRSTVWXYZ12")
        composeRule.waitForIdle()

        composeRule.onNodeWithText(context.getString(R.string.onboarding_link_offer_text, "https://attacker.example"))
            .assertIsDisplayed()
        composeRule.onNodeWithTag("onboardingLinkOfferDiscard").performClick()
        composeRule.onNodeWithTag("onboardingBaseUrl").assertTextContains("https://mine.example")
    }

    @Test
    fun aFilledFormTakesTheLinkOnlyAfterConfirmation() {
        show()
        composeRule.onNodeWithTag("onboardingBaseUrl").performTextInput("https://mine.example")
        link = Triple("Heim", "https://heim.example", "ABCDEFGHJKMNPQRSTVWXYZ12")
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("onboardingBaseUrl").assertTextContains("https://mine.example")
        composeRule.onNodeWithTag("onboardingLinkOfferApply").performClick()
        composeRule.onNodeWithTag("onboardingBaseUrl").assertTextContains("https://heim.example")
    }
}
