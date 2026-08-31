package io.github.excludebarrier.orsikrelay

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

/**
 * Compose instrumentation test for the Mission 001 navigation slice:
 * the app starts on the Onboarding screen and the "Weiter" button
 * navigates to the Sessions screen.
 *
 * Deterministic: no network, no device data, no production content.
 */
@RunWith(AndroidJUnit4::class)
class NavigationUiTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun onboardingIsStartScreenAndWeiterNavigatesToSessions() {
        // 1. Onboarding title is visible at startup.
        composeRule.onNodeWithText("Hermes einrichten").assertIsDisplayed()

        // 2. "Weiter" navigates to Sessions.
        composeRule.onNodeWithText("Weiter").performClick()
        composeRule.onNodeWithText("Sessions").assertIsDisplayed()
    }
}
