package io.github.excludebarrier.orsikrelay

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.excludebarrier.orsikrelay.data.profile.SharedPreferencesServerProfileMetadataStore
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OnboardingDoesNotPersistUiTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val context: Context
        get() = composeRule.activity.applicationContext

    @Before
    fun clearPersistence() {
        context.deleteSharedPreferences(
            SharedPreferencesServerProfileMetadataStore.DEFAULT_PREFERENCES_NAME
        )
        context.deleteSharedPreferences(CREDENTIAL_PREFERENCES_NAME)
    }

    @After
    fun cleanPersistence() {
        clearPersistence()
    }

    @Test
    fun validSubmitNavigatesButDoesNotPersistBeforeConnectionTest() {
        composeRule.onNodeWithTag("onboardingProfileName")
            .performTextInput("Fixture")
        composeRule.onNodeWithTag("onboardingBaseUrl")
            .performTextInput("https://example.invalid/p/test")
        composeRule.onNodeWithContentDescription("API-Key", useUnmergedTree = true)
            .performTextInput("fixture-credential")

        composeRule.onNodeWithTag("onboardingSubmitButton")
            .performScrollTo()
            .performClick()

        composeRule.onNodeWithText("Sessions").assertIsDisplayed()
        val metadata = context.getSharedPreferences(
            SharedPreferencesServerProfileMetadataStore.DEFAULT_PREFERENCES_NAME,
            Context.MODE_PRIVATE
        )
        val credentials = context.getSharedPreferences(
            CREDENTIAL_PREFERENCES_NAME,
            Context.MODE_PRIVATE
        )
        assertTrue(metadata.all.isEmpty())
        assertTrue(credentials.all.isEmpty())
    }

    private companion object {
        const val CREDENTIAL_PREFERENCES_NAME = "hermes_credentials"
    }
}
