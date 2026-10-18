package io.github.excludebarrier.orsikrelay

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.excludebarrier.orsikrelay.ui.onboarding.OnboardingConnectionState
import io.github.excludebarrier.orsikrelay.ui.onboarding.OnboardingFailureReason
import io.github.excludebarrier.orsikrelay.ui.onboarding.OnboardingScreen
import io.github.excludebarrier.orsikrelay.ui.onboarding.onboardingFailureCopy
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The single onboarding error card.
 *
 * Every failure renders one [io.github.excludebarrier.orsikrelay.ui.common.NoticeCard]
 * with a title and exactly one action sentence, both at the same type size.
 * The old two-line message/hint pair no longer exists.
 */
@RunWith(AndroidJUnit4::class)
class OnboardingErrorCardUiTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @get:Rule
    val composeRule = createComposeRule()

    private var state by mutableStateOf<OnboardingConnectionState>(OnboardingConnectionState.Idle)

    @Test
    fun everyFailureKindShowsItsTitleAndTextInOneCard() {
        setOnboarding()

        listOf(
            OnboardingFailureReason.TLS,
            OnboardingFailureReason.INCOMPATIBLE_API,
            OnboardingFailureReason.PAIRING_CODE_INVALID,
            OnboardingFailureReason.HTTP
        ).forEach { reason ->
            composeRule.runOnIdle {
                state = OnboardingConnectionState.Failure(reason)
            }
            val (title, text) = onboardingFailureCopy(reason)
            composeRule.onNodeWithTag("onboardingConnectionError").assertIsDisplayed()
            composeRule.onNodeWithText(context.getString(title), useUnmergedTree = true)
                .assertIsDisplayed()
            composeRule.onNodeWithText(context.getString(text), useUnmergedTree = true)
                .assertIsDisplayed()
            composeRule.onNodeWithTag("onboardingConnectionHint").assertDoesNotExist()
        }
    }

    @Test
    fun titleAndTextShareTheSameFontSize() {
        setOnboarding()

        val (title, text) = onboardingFailureCopy(OnboardingFailureReason.TLS)
        composeRule.runOnIdle {
            state = OnboardingConnectionState.Failure(OnboardingFailureReason.TLS)
        }

        assertEquals(
            "the notice title and its text must use the same type size",
            layoutOf(context.getString(title)).layoutInput.style.fontSize,
            layoutOf(context.getString(text)).layoutInput.style.fontSize
        )
    }

    private fun layoutOf(text: String): TextLayoutResult {
        val layouts = ArrayList<TextLayoutResult>()
        composeRule.onNodeWithText(text, useUnmergedTree = true)
            .fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult]
            .action
            ?.invoke(layouts)
        return layouts.single()
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
