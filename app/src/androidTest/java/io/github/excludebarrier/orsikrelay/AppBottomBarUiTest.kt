package io.github.excludebarrier.orsikrelay

import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import io.github.excludebarrier.orsikrelay.navigation.ACTIVITY_BADGE_TAG
import io.github.excludebarrier.orsikrelay.navigation.AppBottomBar
import io.github.excludebarrier.orsikrelay.navigation.AppScreen
import io.github.excludebarrier.orsikrelay.navigation.bottomTabTag
import io.github.excludebarrier.orsikrelay.ui.theme.DarkStatusColors
import io.github.excludebarrier.orsikrelay.ui.theme.HermesTheme
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The Activity badge is the design's amber dot and only shows for a pending approval. */
class AppBottomBarUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun show(current: AppScreen, approvalPending: Boolean) {
        composeRule.setContent {
            HermesTheme(darkTheme = true) {
                AppBottomBar(current = current, approvalPending = approvalPending, onSelect = {})
            }
        }
    }

    @Test
    fun noBadgeWithoutAPendingApproval() {
        show(AppScreen.Home, approvalPending = false)

        assertEquals(0, composeRule.onAllNodesWithTag(ACTIVITY_BADGE_TAG, useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test
    fun aPendingApprovalShowsAnAmberBadge() {
        show(AppScreen.Home, approvalPending = true)

        val badge = composeRule.onNodeWithTag(ACTIVITY_BADGE_TAG, useUnmergedTree = true).assertIsDisplayed()
        val pixels = badge.captureToImage().toPixelMap()
        val center = pixels[pixels.width / 2, pixels.height / 2]
        val amber = DarkStatusColors.warn
        assertTrue(
            "badge centre $center must be the warn amber $amber",
            abs(center.red - amber.red) < 0.05f && abs(center.green - amber.green) < 0.05f && abs(center.blue - amber.blue) < 0.05f
        )
    }

    @Test
    fun theCurrentTabIsSelected() {
        show(AppScreen.Sessions, approvalPending = false)

        composeRule.onNodeWithTag(bottomTabTag(AppScreen.Sessions)).assertIsSelected()
    }
}
