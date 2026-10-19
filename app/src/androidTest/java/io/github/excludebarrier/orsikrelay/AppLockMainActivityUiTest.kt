package io.github.excludebarrier.orsikrelay

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import io.github.excludebarrier.orsikrelay.data.settings.AppLockSettingStore
import io.github.excludebarrier.orsikrelay.security.applock.AppLockHolder
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test

/**
 * The real activity wiring: a cold start with the persisted lock enabled
 * shows only the lock screen, and a successful authentication removes the
 * gate. The preference store and the authenticator are test seams, so neither
 * the device preference nor the system prompt is touched.
 */
class AppLockMainActivityUiTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun coldStartLocksAndASuccessfulAuthenticationRemovesTheGate() {
        composeRule.onNodeWithTag("app_lock_screen").assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.app_lock_locked_title))
            .assertIsDisplayed()
        assertNoNavHostContent()

        // The automatic prompt is pending; the unlock button adds a second
        // request, which the fake answers successfully.
        composeRule.runOnUiThread { authenticator.complete(false) }
        composeRule.onNodeWithTag("app_lock_unlock").performClick()
        composeRule.runOnIdle { assertEquals(2, authenticator.callCount) }
        composeRule.runOnUiThread { authenticator.complete(true) }

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("app_lock_screen").fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithText(context.getString(R.string.app_lock_locked_title))
            .assertDoesNotExist()
    }

    private fun assertNoNavHostContent() {
        listOf(
            R.string.onboarding_title,
            R.string.sessions_title,
            R.string.settings_privacy_section
        ).forEach { stringRes ->
            assertTrue(
                "NavHost content must not be composed while locked",
                composeRule.onAllNodesWithText(context.getString(stringRes))
                    .fetchSemanticsNodes()
                    .isEmpty()
            )
        }
    }

    private companion object {
        lateinit var authenticator: FakeAppAuthenticator

        @BeforeClass
        @JvmStatic
        fun installFakes() {
            authenticator = FakeAppAuthenticator()
            MainActivity.appLockStoreForTests = object : AppLockSettingStore {
                override fun read(): Boolean = true
                override fun write(enabled: Boolean) = Unit
            }
            MainActivity.appAuthenticatorForTests = authenticator
            AppLockHolder.resetForTest()
        }

        @AfterClass
        @JvmStatic
        fun removeFakes() {
            MainActivity.resetAppLockForTests()
        }
    }
}
