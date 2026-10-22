package io.github.excludebarrier.orsikrelay

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import io.github.excludebarrier.orsikrelay.ui.onboarding.WELCOME_ENTER_CODE_TAG
import io.github.excludebarrier.orsikrelay.ui.onboarding.WELCOME_SCAN_TAG
import io.github.excludebarrier.orsikrelay.ui.onboarding.WelcomeScreen
import io.github.excludebarrier.orsikrelay.ui.theme.HermesTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Welcome only routes into the existing pairing flow; it connects nothing itself. */
class WelcomeScreenUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var scans = 0
    private var codes = 0

    private fun show(cameraAvailable: Boolean) {
        composeRule.setContent {
            HermesTheme {
                WelcomeScreen(onScan = { scans++ }, onEnterCode = { codes++ }, cameraAvailable = cameraAvailable)
            }
        }
    }

    @Test
    fun bothEntriesLeadIntoThePairingFlow() {
        show(cameraAvailable = true)

        // Both entries are pinned on screen, no scrolling needed.
        composeRule.onNodeWithTag(WELCOME_SCAN_TAG).assertIsDisplayed().performClick()
        composeRule.onNodeWithTag(WELCOME_ENTER_CODE_TAG).assertIsDisplayed().performClick()

        assertEquals(1, scans)
        assertEquals(1, codes)
    }

    @Test
    fun withoutACameraOnlyTheCodeEntryIsOffered() {
        show(cameraAvailable = false)

        assertEquals(0, composeRule.onAllNodesWithTag(WELCOME_SCAN_TAG).fetchSemanticsNodes().size)
        composeRule.onNodeWithTag(WELCOME_ENTER_CODE_TAG).assertIsDisplayed()
    }
}
