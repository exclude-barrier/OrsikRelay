package io.github.excludebarrier.orsikrelay

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
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
 * - Both segments are exactly the same height and offer a 48 dp touch
 *   target, at font scale 1.0 and 1.3.
 * - The pairing segment label stays on one line; its recommendation is a
 *   separate badge below the segment instead of inline text.
 * - The selected state is exposed through semantics and reinforced by
 *   the segment colour and a semibold label, not colour alone.
 * - The selected segment renders no check mark: its label stays centred
 *   in the segment instead of being shifted by an icon slot.
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
            context.getString(R.string.onboarding_mode_pairing),
            segmentTag = "onboardingModePairing"
        )
    }

    @Test
    fun theRecommendedBadgeSitsBelowThePairingSegment() {
        setOnboarding(fontScale = 1.0f)

        val pairing = composeRule.onNodeWithTag("onboardingModePairing")
            .fetchSemanticsNode().boundsInRoot
        val badge = composeRule.onNodeWithTag("onboardingModePairingRecommended")
            .fetchSemanticsNode().boundsInRoot
        assertTrue(
            "the recommended badge must sit below the pairing segment",
            badge.top >= pairing.bottom
        )
        val maxOffset = with(composeRule.density) { 8.dp.toPx() }
        assertTrue(
            "the recommended badge centre ${badge.center.x} must be under the " +
                "pairing segment centre ${pairing.center.x}",
            kotlin.math.abs(badge.center.x - pairing.center.x) <= maxOffset
        )
        composeRule.onNodeWithTag("onboardingModePairingRecommended")
            .assertTextContains(
                context.getString(R.string.onboarding_mode_pairing_recommended)
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
     * at most [MAX_LABEL_OFFSET_DP] away from the segment's centre. A visible
     * check mark or an empty icon slot would shift the label by about 16 dp;
     * this tight bound therefore also proves the selected segment renders no
     * icon. Wrapped lines share one centre.
     */
    private fun assertCenteredLabel(label: String, segmentTag: String) {
        val textNode = composeRule.onNode(
            hasText(label) and hasAnyAncestor(hasTestTag(segmentTag)),
            useUnmergedTree = true
        )
        val node = textNode.fetchSemanticsNode()
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

        val pairingLabel = composeRule.onNode(
            hasText(context.getString(R.string.onboarding_mode_pairing)) and
                hasAnyAncestor(hasTestTag("onboardingModePairing")),
            useUnmergedTree = true
        ).fetchSemanticsNode()
        val layouts = ArrayList<TextLayoutResult>()
        pairingLabel.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        assertEquals(
            "the pairing segment label must stay on one line",
            1,
            layouts.single().lineCount
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
        /**
         * Allowed sub-pixel drift of a centred label. Anything larger would
         * mean the segment still reserves space for a check-mark icon.
         */
        const val MAX_LABEL_OFFSET_DP = 2f
    }
}
