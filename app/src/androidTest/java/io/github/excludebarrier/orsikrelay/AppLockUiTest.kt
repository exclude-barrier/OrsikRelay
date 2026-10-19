package io.github.excludebarrier.orsikrelay

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import io.github.excludebarrier.orsikrelay.data.session.StartupProfile
import io.github.excludebarrier.orsikrelay.domain.device.PairingBootstrap
import io.github.excludebarrier.orsikrelay.domain.session.HermesSession
import io.github.excludebarrier.orsikrelay.domain.session.SessionListResult
import io.github.excludebarrier.orsikrelay.domain.session.SessionPage
import io.github.excludebarrier.orsikrelay.domain.session.SessionPageSource
import io.github.excludebarrier.orsikrelay.navigation.AppScreen
import io.github.excludebarrier.orsikrelay.navigation.HermesNavHost
import io.github.excludebarrier.orsikrelay.security.applock.AppLockController
import io.github.excludebarrier.orsikrelay.ui.lock.AppLockGate
import io.github.excludebarrier.orsikrelay.ui.theme.HermesTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The lock gate keeps the whole navigation host out of the composition while
 * locked, requests the prompt once on appearance, and only opens the app after
 * a successful authentication.
 */
class AppLockUiTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun lockedScreenContainsNoAppContent() {
        val controller = lockedController()
        val authenticator = FakeAppAuthenticator(autoResult = false)

        setApp(controller, authenticator)

        composeRule.onNodeWithTag("app_lock_screen").assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.app_lock_locked_title))
            .assertIsDisplayed()
        assertEquals(
            0,
            composeRule.onAllNodesWithText(SESSION_TITLE).fetchSemanticsNodes().size
        )
        assertTrue(controller.locked)
    }

    @Test
    fun thePromptStartsAutomaticallyExactlyOnce() {
        val controller = lockedController()
        val authenticator = FakeAppAuthenticator(autoResult = false)

        setApp(controller, authenticator)
        composeRule.waitForIdle()

        assertEquals(1, authenticator.callCount)
    }

    @Test
    fun successfulAuthenticationShowsTheAppContent() {
        val controller = lockedController()
        val authenticator = FakeAppAuthenticator(autoResult = true)

        setApp(controller, authenticator)
        waitForText(SESSION_TITLE)

        composeRule.onNodeWithTag("app_lock_screen").assertDoesNotExist()
        assertFalse(controller.locked)
        assertEquals(1, authenticator.callCount)
    }

    @Test
    fun cancellingStaysLockedAndTheButtonRequestsThePromptAgain() {
        val controller = lockedController()
        // The automatic request stays pending so the cancel and the button
        // press can be observed separately.
        val authenticator = FakeAppAuthenticator()

        setApp(controller, authenticator)
        composeRule.waitUntil(timeoutMillis = 5_000) { authenticator.callCount == 1 }

        composeRule.runOnUiThread { authenticator.complete(false) }
        composeRule.waitForIdle()

        assertTrue(controller.locked)
        composeRule.onNodeWithTag("app_lock_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("app_lock_unlock").assertIsDisplayed()

        composeRule.onNodeWithTag("app_lock_unlock").performClick()
        composeRule.runOnIdle { assertEquals(2, authenticator.callCount) }

        composeRule.runOnUiThread { authenticator.complete(true) }
        waitForText(SESSION_TITLE)
        assertFalse(controller.locked)
    }

    @Test
    fun aHeldBackPairingDeepLinkIsProcessedAfterUnlocking() {
        val controller = lockedController()
        val authenticator = FakeAppAuthenticator()
        val bootstrap = PairingBootstrap(
            baseUrl = "https://hermes.example.test",
            pairingCode = "ABCD-EFGH-JKMN-PQRS-TVWX-YZ12"
        )

        composeRule.setContent {
            HermesTheme {
                AppLockGate(controller, authenticator) {
                    HermesNavHost(
                        startDestination = AppScreen.Onboarding,
                        startupProfileResolver = { StartupProfile.None },
                        pairingBootstrap = bootstrap
                    )
                }
            }
        }

        composeRule.waitUntil(timeoutMillis = 5_000) { authenticator.callCount == 1 }
        composeRule.runOnUiThread { authenticator.complete(false) }
        composeRule.waitForIdle()
        assertEquals(
            0,
            composeRule.onAllNodesWithText(context.getString(R.string.onboarding_title))
                .fetchSemanticsNodes().size
        )

        composeRule.onNodeWithTag("app_lock_unlock").performClick()
        composeRule.runOnUiThread { authenticator.complete(true) }
        waitForText(context.getString(R.string.onboarding_title))

        composeRule.onNodeWithTag("onboardingBaseUrl")
            .assertTextContains(bootstrap.baseUrl)
    }

    @Test
    fun aRemovedDeviceLockTurnsTheAppLockOffInsteadOfLockingOut() {
        val persisted = mutableListOf<Boolean>()
        val controller = AppLockController(initiallyEnabled = true, persist = { persisted += it })
        val authenticator = FakeAppAuthenticator(available = false)

        setApp(controller, authenticator)

        composeRule.onNodeWithText(context.getString(R.string.app_lock_device_lock_missing))
            .assertIsDisplayed()
        assertEquals("no prompt without a device lock", 0, authenticator.callCount)
        assertEquals(
            0,
            composeRule.onAllNodesWithText(SESSION_TITLE).fetchSemanticsNodes().size
        )

        composeRule.onNodeWithTag("app_lock_continue").performClick()
        composeRule.waitForIdle()

        assertTrue(!controller.locked)
        assertTrue(!controller.enabled)
        assertEquals(listOf(false), persisted)
        composeRule.onNodeWithTag("app_lock_device_lock_missing").assertDoesNotExist()
    }

    @Test
    fun aBrieflyUnavailableSensorKeepsTheLockInsteadOfTurningItOff() {
        val persisted = mutableListOf<Boolean>()
        val controller = AppLockController(initiallyEnabled = true, persist = { persisted += it })
        // The device has a screen lock, but canAuthenticate fails for now
        // (e.g. the sensor is busy right after boot).
        val authenticator = FakeAppAuthenticator(available = false, lockMissing = false)

        setApp(controller, authenticator)

        composeRule.onNodeWithTag("app_lock_unlock").assertIsDisplayed()
        composeRule.onNodeWithTag("app_lock_device_lock_missing").assertDoesNotExist()
        assertTrue(controller.locked)
        assertTrue(controller.enabled)
        assertEquals("the app lock is never switched off", emptyList<Boolean>(), persisted)
    }

    private fun lockedController(): AppLockController =
        AppLockController(initiallyEnabled = true, persist = {})

    private fun setApp(
        controller: AppLockController,
        authenticator: FakeAppAuthenticator
    ) {
        composeRule.setContent {
            HermesTheme {
                AppLockGate(controller, authenticator) {
                    HermesNavHost(
                        startDestination = AppScreen.Onboarding,
                        sessionPageSource = sessionSource(),
                        startupProfileResolver = { StartupProfile.Ready("fixture-profile") }
                    )
                }
            }
        }
    }

    private fun sessionSource(): SessionPageSource = SessionPageSource { _, _, _ ->
        SessionListResult.Success(
            SessionPage(
                sessions = listOf(
                    HermesSession(
                        id = "fixture-session",
                        title = SESSION_TITLE,
                        source = null,
                        lastActiveEpochSeconds = null,
                        messageCount = null,
                        pinned = false
                    )
                ),
                hasMore = false
            )
        )
    }

    private fun waitForText(text: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()
    }

    private companion object {
        const val SESSION_TITLE = "Locked session title"
    }
}
