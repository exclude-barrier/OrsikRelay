package io.github.excludebarrier.orsikrelay

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import io.github.excludebarrier.orsikrelay.data.profile.VerifiedProfileSaver
import io.github.excludebarrier.orsikrelay.domain.connection.ConnectionFailureReason
import io.github.excludebarrier.orsikrelay.domain.connection.ConnectionProbe
import io.github.excludebarrier.orsikrelay.domain.connection.HermesConnectionResult
import io.github.excludebarrier.orsikrelay.domain.connection.ProfileConnectionCoordinator
import io.github.excludebarrier.orsikrelay.domain.profile.ServerProfile
import io.github.excludebarrier.orsikrelay.ui.onboarding.OnboardingRoute
import io.github.excludebarrier.orsikrelay.ui.theme.HermesTheme
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class OnboardingConnectionUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun fakeSuccessSavesOnceAndEmitsConnectedDestination() {
        val probeCalls = AtomicInteger()
        val saveCalls = AtomicInteger()
        val coordinator = coordinator(
            probe = {
                probeCalls.incrementAndGet()
                HermesConnectionResult.Success("0.20-test", true, true, true)
            },
            saver = {
                saveCalls.incrementAndGet()
                profile()
            }
        )
        setRoute(coordinator)

        submitValidForm()
        waitForText("Sessions")

        assertEquals(1, probeCalls.get())
        assertEquals(1, saveCalls.get())
    }

    @Test
    fun invalidCredentialsStayOnOnboardingAndNeverSave() {
        val saveCalls = AtomicInteger()
        val coordinator = coordinator(
            probe = {
                HermesConnectionResult.Failure(
                    ConnectionFailureReason.INVALID_CREDENTIALS
                )
            },
            saver = {
                saveCalls.incrementAndGet()
                profile()
            }
        )
        setRoute(coordinator)

        submitValidForm()
        waitForText("Der API-Key wurde abgelehnt.")

        composeRule.onNodeWithText("Server hinzufügen").assertIsDisplayed()
        assertEquals(0, saveCalls.get())
    }

    @Test
    fun persistenceFailureStaysOnOnboarding() {
        val coordinator = coordinator(
            probe = {
                HermesConnectionResult.Success("0.20-test", true, true, true)
            },
            saver = {
                throw IllegalStateException("synthetic persistence failure")
            }
        )
        setRoute(coordinator)

        submitValidForm()
        waitForText("Der Server konnte nicht sicher gespeichert werden.")

        composeRule.onNodeWithText("Server hinzufügen").assertIsDisplayed()
    }

    @Test
    fun connectingDisablesSubmitAndDoesNotStartASecondProbe() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val probeCalls = AtomicInteger()
        val coordinator = coordinator(
            probe = {
                probeCalls.incrementAndGet()
                started.countDown()
                release.await(3, TimeUnit.SECONDS)
                HermesConnectionResult.Success("0.20-test", true, true, true)
            },
            saver = { profile() }
        )
        setRoute(coordinator)

        submitValidForm()
        assertTrue(started.await(3, TimeUnit.SECONDS))
        waitForText("Verbindung wird geprüft…")
        composeRule.onNodeWithTag("onboardingSubmitButton").assertIsNotEnabled()
        assertEquals(1, probeCalls.get())

        release.countDown()
        waitForText("Sessions")
        assertEquals(1, probeCalls.get())
    }

    private fun setRoute(coordinator: ProfileConnectionCoordinator) {
        composeRule.setContent {
            HermesTheme {
                var connected by remember { mutableStateOf(false) }
                if (connected) {
                    Text("Sessions")
                } else {
                    OnboardingRoute(
                        connect = { draft -> coordinator.connect(draft) },
                        onConnected = { connected = true }
                    )
                }
            }
        }
    }

    private fun submitValidForm() {
        composeRule.onNodeWithTag("onboardingProfileName")
            .performTextInput("Fixture")
        composeRule.onNodeWithTag("onboardingBaseUrl")
            .performTextInput(BASE_URL)
        composeRule.onNodeWithContentDescription("API-Key", useUnmergedTree = true)
            .performTextInput(FIXTURE_KEY)
        composeRule.onNodeWithTag("onboardingSubmitButton")
            .performScrollTo()
            .performClick()
    }

    private fun waitForText(text: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()
    }

    private fun coordinator(
        probe: (io.github.excludebarrier.orsikrelay.domain.profile.ProfileDraft) -> HermesConnectionResult,
        saver: (io.github.excludebarrier.orsikrelay.domain.profile.ProfileDraft) -> ServerProfile
    ) = ProfileConnectionCoordinator(
        connectionProbe = ConnectionProbe { draft -> probe(draft) },
        profileSaver = VerifiedProfileSaver { draft -> saver(draft) }
    )

    private fun profile() = ServerProfile.create(
        id = "fixture-profile-id",
        profileName = "Fixture",
        baseUrl = BASE_URL
    )

    private companion object {
        const val BASE_URL = "https://example.invalid/p/test"
        const val FIXTURE_KEY = "fixture-credential"
    }
}
