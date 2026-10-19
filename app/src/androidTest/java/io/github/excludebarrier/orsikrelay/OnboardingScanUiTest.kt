package io.github.excludebarrier.orsikrelay

import android.net.Uri
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.excludebarrier.orsikrelay.ui.onboarding.OnboardingScreen
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The "Scan QR code" action of the pairing form.
 *
 * The camera itself is not exercised here: [OnboardingScreen.scanFrames] is
 * the injected scan source. The acceptance path still runs through the real
 * [io.github.excludebarrier.orsikrelay.domain.device.PairingDeepLink] parser
 * and the real scanner validation/hint UI.
 */
@RunWith(AndroidJUnit4::class)
class OnboardingScanUiTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @get:Rule
    val composeRule = createComposeRule()

    private var pairingCalls = 0
    private var connectedBaseUrl = ""
    private var connectedCode = ""

    @Test
    fun theScanButtonIsOnlyVisibleInThePairingMode() {
        setOnboarding(scanFrames = null)

        composeRule.onNodeWithTag("onboardingScanQr")
            .performScrollTo()
            .assertIsDisplayed()

        composeRule.onNodeWithTag("onboardingModeApiKey").performClick()
        composeRule.onNodeWithTag("onboardingScanQr").assertDoesNotExist()

        composeRule.onNodeWithTag("onboardingModePairing").performClick()
        composeRule.onNodeWithTag("onboardingScanQr").assertExists()
    }

    @Test
    fun withoutACameraTheScanButtonIsHidden() {
        setOnboarding(scanFrames = null, cameraAvailable = false)

        composeRule.onNodeWithTag("onboardingScanQr").assertDoesNotExist()
        composeRule.onNodeWithTag("onboardingModePairing").assertIsDisplayed()
    }

    @Test
    fun anInjectedValidScanFillsUrlAndCodeWithoutConnecting() {
        val link = pairingLink()
        setOnboarding(scanFrames = flowOf(link))

        composeRule.onNodeWithTag("onboardingScanQr")
            .performScrollTo()
            .performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule
                .onAllNodes(hasText(context.getString(R.string.onboarding_scan_title)))
                .fetchSemanticsNodes()
                .isEmpty()
        }

        assertEquals(SERVER, fieldText("onboardingBaseUrl"))
        // The code field is password-masked, so only the length is readable.
        assertEquals(PAIRING_CODE.length, fieldText("onboardingPairingCode").length)
        assertEquals("the scan must not connect on its own", 0, pairingCalls)

        // The server name stays a user decision; the scan never fills it.
        composeRule.onNodeWithTag("onboardingProfileName")
            .performScrollTo()
            .performTextReplacement("Fixture")

        // Pressing Connect explicitly hands the scanned values to the
        // existing pairing path.
        composeRule.onNodeWithTag("onboardingSubmitButton")
            .performScrollTo()
            .performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { pairingCalls == 1 }
        assertEquals(SERVER, connectedBaseUrl)
        assertEquals(PAIRING_CODE, connectedCode)
    }

    @Test
    fun anInjectedForeignPayloadShowsTheClosedHintAndKeepsScanning() {
        setOnboarding(scanFrames = flowOf("https://example.invalid/not-orsik"))

        composeRule.onNodeWithTag("onboardingScanQr")
            .performScrollTo()
            .performClick()

        val hint = context.getString(R.string.onboarding_scan_invalid)
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodes(hasText(hint)).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(hint).assertIsDisplayed()
        // The scanner stays open and nothing was connected or pre-filled.
        composeRule.onNodeWithText(context.getString(R.string.onboarding_scan_title))
            .assertIsDisplayed()
        assertEquals(0, pairingCalls)
        composeRule.onNodeWithTag("onboardingBaseUrl").assertDoesNotExist()
    }

    private fun fieldText(tag: String): String =
        composeRule.onNodeWithTag(tag)
            .fetchSemanticsNode()
            .config[SemanticsProperties.EditableText]
            .text

    private fun setOnboarding(
        scanFrames: kotlinx.coroutines.flow.Flow<String>?,
        cameraAvailable: Boolean = true
    ) {
        pairingCalls = 0
        connectedBaseUrl = ""
        connectedCode = ""
        composeRule.setContent {
            OnboardingScreen(
                onValidDraft = {},
                onValidPairing = { _, baseUrl, code ->
                    pairingCalls += 1
                    connectedBaseUrl = baseUrl
                    connectedCode = code
                },
                cameraAvailable = cameraAvailable,
                scanFrames = scanFrames
            )
        }
    }

    private fun pairingLink(): String =
        "orsik://pair?server=" + Uri.encode(SERVER) + "&code=" + PAIRING_CODE

    private companion object {
        const val SERVER = "https://server.example"
        const val PAIRING_CODE = "AB12CD34EF56GH78JK9MNPQR"
    }
}
