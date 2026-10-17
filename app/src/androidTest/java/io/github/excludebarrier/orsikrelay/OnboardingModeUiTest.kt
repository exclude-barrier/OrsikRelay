package io.github.excludebarrier.orsikrelay

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.excludebarrier.orsikrelay.ui.onboarding.OnboardingScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The onboarding mode selector and the mode explanation dialog.
 *
 * - Both segments are exactly the same height (never the wrapped
 *   "Pairing code (recommended)" label making the left segment taller)
 *   and offer a 48 dp touch target, at font scale 1.0 and 1.3.
 * - The selected state is exposed through semantics and reinforced by
 *   the built-in check mark and a semibold label, not colour alone.
 * - The info button reaches the explanation dialog; OK closes it and
 *   neither mode nor fields change.
 */
@RunWith(AndroidJUnit4::class)
class OnboardingModeUiTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun segmentsHaveEqualHeightAndSelectedSemanticsAtNormalFont() {
        setOnboarding(fontScale = 1.0f)
        assertSegments()
    }

    @Test
    fun segmentsHaveEqualHeightAndSelectedSemanticsAtLargeFont() {
        setOnboarding(fontScale = 1.3f)
        assertSegments()
    }

    @Test
    fun bothSegmentLabelsAreCentered() {
        setOnboarding(fontScale = 1.0f)
        assertCenteredLabel(
            context.getString(R.string.onboarding_mode_api_key),
            segmentTag = "onboardingModeApiKey"
        )
        assertCenteredLabel(
            context.getString(R.string.onboarding_mode_pairing) +
                " (" + context.getString(R.string.onboarding_mode_pairing_recommended) + ")",
            segmentTag = "onboardingModePairing"
        )
    }

    @Test
    fun infoButtonOpensTheExplanationAndOkClosesItWithoutChangingTheForm() {
        setOnboarding(fontScale = 1.0f)
        composeRule.onNodeWithTag("onboardingProfileName")
            .performTextReplacement("Fixture")

        val info = composeRule.onNodeWithTag("onboardingModeInfo")
        val minTouchTarget = with(composeRule.density) { 48.dp.toPx() }
        val bounds = info.fetchSemanticsNode().boundsInRoot
        assertTrue(
            "info button width ${bounds.width}px below 48 dp",
            bounds.width >= minTouchTarget
        )
        assertTrue(
            "info button height ${bounds.height}px below 48 dp",
            bounds.height >= minTouchTarget
        )
        assertTrue(
            "info button exposes a content description",
            info.fetchSemanticsNode()
                .config[SemanticsProperties.ContentDescription]
                .contains(context.getString(R.string.onboarding_mode_info_content_description))
        )

        info.performClick()
        composeRule.onNodeWithText(context.getString(R.string.onboarding_mode_info_title))
            .assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.onboarding_mode_info_pairing_body))
            .assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.onboarding_mode_info_api_key_body))
            .assertIsDisplayed()

        composeRule.onNodeWithText(context.getString(R.string.onboarding_mode_info_ok)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.onboarding_mode_info_title))
            .assertDoesNotExist()

        // Neither the selected mode nor the entered field changed.
        assertEquals(
            true,
            composeRule.onNodeWithTag("onboardingModePairing")
                .fetchSemanticsNode().config[SemanticsProperties.Selected]
        )
        assertFalse(
            composeRule.onNodeWithTag("onboardingModeApiKey")
                .fetchSemanticsNode().config[SemanticsProperties.Selected]
        )
        composeRule.onNodeWithTag("onboardingProfileName").assertTextContains("Fixture")
        composeRule.onNodeWithTag("onboardingApiKey").assertDoesNotExist()
    }

    /**
     * The label sits in the middle of its segment: the text box's centre is
     * at most [MAX_LABEL_OFFSET_DP] away from the segment's centre (the
     * selected segment's check mark shifts the label by half its width), and
     * wrapped lines share one centre.
     */
    private fun assertCenteredLabel(label: String, segmentTag: String) {
        val node = composeRule.onNodeWithText(label, useUnmergedTree = true)
            .fetchSemanticsNode()
        val segment = composeRule.onNodeWithTag(segmentTag).fetchSemanticsNode().boundsInRoot
        val offsetDp = with(composeRule.density) {
            kotlin.math.abs(node.boundsInRoot.center.x - segment.center.x).toDp().value
        }
        assertTrue(
            "'$label' is ${offsetDp}dp off the centre of its segment",
            offsetDp <= MAX_LABEL_OFFSET_DP
        )
        val action = node.config[SemanticsActions.GetTextLayoutResult]
        val layouts = ArrayList<TextLayoutResult>()
        action.action?.invoke(layouts)
        val layout = layouts.single()
        // Wrapped lines share one centre (TextAlign.Center).
        val centres = (0 until layout.lineCount).map { line ->
            (layout.getLineLeft(line) + layout.getLineRight(line)) / 2f
        }
        assertTrue(
            "the lines of '$label' are not centred on each other: $centres",
            centres.max() - centres.min() <= 2f
        )
    }

    private fun assertSegments() {
        val pairing = composeRule.onNodeWithTag("onboardingModePairing")
        val apiKey = composeRule.onNodeWithTag("onboardingModeApiKey")
        val pairingBounds = pairing.fetchSemanticsNode().boundsInRoot
        val apiKeyBounds = apiKey.fetchSemanticsNode().boundsInRoot

        assertEquals(
            "the two mode segments must be equally high",
            pairingBounds.height,
            apiKeyBounds.height,
            1f
        )
        val minTouchTarget = with(composeRule.density) { 48.dp.toPx() }
        assertTrue(
            "pairing segment height ${pairingBounds.height}px below 48 dp",
            pairingBounds.height >= minTouchTarget
        )
        assertTrue(
            "API-key segment height ${apiKeyBounds.height}px below 48 dp",
            apiKeyBounds.height >= minTouchTarget
        )

        assertEquals(
            true,
            pairing.fetchSemanticsNode().config[SemanticsProperties.Selected]
        )
        assertFalse(
            apiKey.fetchSemanticsNode().config[SemanticsProperties.Selected]
        )

        apiKey.performClick()
        assertEquals(
            true,
            apiKey.fetchSemanticsNode().config[SemanticsProperties.Selected]
        )
        assertFalse(
            pairing.fetchSemanticsNode().config[SemanticsProperties.Selected]
        )
    }

    private fun setOnboarding(fontScale: Float) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale)
            ) {
                OnboardingScreen(
                    onValidDraft = {},
                    onValidPairing = { _, _, _ -> }
                )
            }
        }
    }

    private companion object {
        /** Half of the check mark plus its spacing in a selected segment. */
        const val MAX_LABEL_OFFSET_DP = 16f
    }
}
