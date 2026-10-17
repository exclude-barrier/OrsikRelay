package io.github.excludebarrier.orsikrelay

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.excludebarrier.orsikrelay.domain.connection.ConnectionFailureReason
import io.github.excludebarrier.orsikrelay.domain.connection.ProfileConnectionOutcome
import io.github.excludebarrier.orsikrelay.domain.device.DevicePairError
import io.github.excludebarrier.orsikrelay.domain.device.DevicePairingFailure
import io.github.excludebarrier.orsikrelay.domain.device.DevicePairingOutcome
import io.github.excludebarrier.orsikrelay.ui.onboarding.OnboardingRoute
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The onboarding pairing UI contract.
 *
 * - The pairing mode is VISIBLE and the DEFAULT; in it there is no
 *   API-key field, and the pairing code is password-protected and never
 *   rendered as plain text.
 * - Switching to the API-key mode restores the pre-existing path
 *   (API-key field present, pairing-code field gone) and switching back
 *   keeps the two secrets independent.
 * - A successful pairing is reported through the same verified
 *   `onConnected` path exactly once; a double tap cannot start a second
 *   request while the first is in flight.
 * - Failures show the closed German messages, never raw values.
 *
 * Only synthetic values are used.
 */
@RunWith(AndroidJUnit4::class)
class OnboardingPairingUiTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @get:Rule
    val composeRule = createComposeRule()

    private val pairingCallCount = AtomicInteger()
    private val connectedProfiles = mutableListOf<ProfileConnectionOutcome.Success>()

    @Before
    fun reset() {
        pairingCallCount.set(0)
        connectedProfiles.clear()
    }

    @Test
    fun pairingModeIsTheVisibleDefaultAndHidesTheApiKeyField() {
        setContent { DevicePairingOutcome.Failure(DevicePairingFailure.InvalidInput) }

        // The recommendation hint is visible, the pairing-code field is
        // present with password semantics, and the API-key field does not
        // exist in this mode at all.
        composeRule.onNodeWithText(
            context.getString(R.string.onboarding_mode_pairing) +
                " (" + context.getString(R.string.onboarding_mode_pairing_recommended) + ")"
        ).assertIsDisplayed()
        assertPasswordField()
        composeRule.onNodeWithTag("onboardingApiKey").assertDoesNotExist()
    }

    @Test
    fun switchingToApiKeyModeRestoresThePreexistingPathAndBack() {
        setContent { DevicePairingOutcome.Failure(DevicePairingFailure.InvalidInput) }

        composeRule.onNodeWithTag("onboardingModeApiKey").performClick()
        composeRule.onNodeWithTag("onboardingApiKey").assertExists()
        composeRule.onNodeWithTag("onboardingPairingCode").assertDoesNotExist()

        // Back to pairing: the code field returns, the API-key field is
        // gone again. The two secrets never share a field.
        composeRule.onNodeWithTag("onboardingModePairing").performClick()
        composeRule.onNodeWithTag("onboardingPairingCode").assertExists()
        composeRule.onNodeWithTag("onboardingApiKey").assertDoesNotExist()
    }

    @Test
    fun aValidPairingSuccessIsReportedExactlyOnceThroughTheSharedPath() {
        composeRule.setContent {
            OnboardingRoute(
                connect = {
                    org.junit.Assert.fail("the API-key path must not be used in pairing mode")
                    ProfileConnectionOutcome.Failure(ConnectionFailureReason.INVALID_INPUT)
                },
                connectPairing = { _, _, _ ->
                    pairingCallCount.incrementAndGet()
                    DevicePairingOutcome.Success(
                        version = "0.20-test",
                        profileId = "paired-profile",
                        sessionChatInteractiveApprovals = false
                    )
                },
                onConnected = { connectedProfiles += it }
            )
        }

        fillPairingForm()
        composeRule.onNodeWithTag("onboardingSubmitButton")
            .performScrollTo()
            .performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) { connectedProfiles.size == 1 }
        val success = connectedProfiles.single()
        assertEquals("paired-profile", success.profileId)
        assertEquals("0.20-test", success.version)
        assertEquals(1, pairingCallCount.get())
    }

    @Test
    fun aDoubleTapCannotStartASecondPairingRequest() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        composeRule.setContent {
            OnboardingRoute(
                connect = { ProfileConnectionOutcome.Failure(ConnectionFailureReason.INVALID_INPUT) },
                connectPairing = { _, _, _ ->
                    pairingCallCount.incrementAndGet()
                    started.countDown()
                    release.await(3, TimeUnit.SECONDS)
                    DevicePairingOutcome.Failure(DevicePairingFailure.InvalidInput)
                },
                onConnected = { connectedProfiles += it }
            )
        }

        fillPairingForm()
        composeRule.onNodeWithTag("onboardingSubmitButton")
            .performScrollTo()
            .performClick()
        assertTrue("the pairing request started", started.await(3, TimeUnit.SECONDS))
        waitForText(context.getString(R.string.onboarding_connecting))
        composeRule.onNodeWithTag("onboardingSubmitButton").assertIsNotEnabled()
        assertEquals(1, pairingCallCount.get())
        // Double tap: the button is disabled, so no second request starts.
        composeRule.onNodeWithTag("onboardingSubmitButton").performClick()
        assertEquals(1, pairingCallCount.get())

        release.countDown()
        waitForText(context.getString(R.string.error_connection_invalid_input))
        assertEquals(1, pairingCallCount.get())
    }

    @Test
    fun pairingFailuresShowClosedGermanMessagesWithoutRawValues() {
        // One setContent per test (createComposeRule): the single route
        // below is driven by a mutable outcome, so all nine closed failure
        // cases run against the same composition.
        var outcome: DevicePairingOutcome =
            DevicePairingOutcome.Failure(DevicePairingFailure.InvalidInput)
        composeRule.setContent {
            OnboardingRoute(
                connect = { ProfileConnectionOutcome.Failure(ConnectionFailureReason.INVALID_INPUT) },
                connectPairing = { _, _, _ -> outcome },
                onConnected = {
                    org.junit.Assert.fail("the failure path must not connect")
                    Unit
                }
            )
        }

        val cases = listOf(
            DevicePairingFailure.Pairing(DevicePairError.PairingCodeInvalid) to
                context.getString(R.string.error_pairing_code_invalid),
            DevicePairingFailure.Pairing(DevicePairError.DeviceLimitExceeded) to
                context.getString(R.string.error_pairing_device_limit),
            DevicePairingFailure.Pairing(DevicePairError.PairRateLimited) to
                context.getString(R.string.error_pairing_rate_limited),
            DevicePairingFailure.Pairing(DevicePairError.Tls) to
                context.getString(R.string.error_connection_tls),
            DevicePairingFailure.Pairing(DevicePairError.Timeout) to
                context.getString(R.string.error_connection_timeout),
            DevicePairingFailure.Pairing(DevicePairError.Network) to
                context.getString(R.string.error_connection_network),
            DevicePairingFailure.IncompatiblePairing to context.getString(R.string.error_pairing_incompatible_api),
            DevicePairingFailure.Persistence to context.getString(R.string.error_connection_persistence),
            DevicePairingFailure.Verification(ConnectionFailureReason.INVALID_CREDENTIALS) to
                context.getString(R.string.error_pairing_credential_rejected)
        )
        cases.forEach { (failure, expectedText) ->
            outcome = DevicePairingOutcome.Failure(failure)
            fillPairingForm()
            composeRule.onNodeWithTag("onboardingSubmitButton")
                .performScrollTo()
                .performClick()
            waitForText(expectedText)
            // The closed banner must not contain any raw fixture value.
            composeRule
                .onAllNodes(hasText(expectedText, substring = true))
                .fetchSemanticsNodes()
                .forEach { node ->
                    val texts = node.config[SemanticsProperties.Text]
                    assertTrue(
                        "no raw URL in the UI for $expectedText",
                        texts?.none { it.text.contains("example.invalid") } ?: false
                    )
                }
        }
    }

    @Test
    fun anInvalidPairingCodeShowsTheLocalClosedErrorAndStartsNoRequest() {
        setContent { DevicePairingOutcome.Failure(DevicePairingFailure.InvalidInput) }

        fillPairingForm(code = "not a code")
        composeRule.onNodeWithTag("onboardingSubmitButton")
            .performScrollTo()
            .performClick()

        composeRule
            .onNodeWithText(context.getString(R.string.error_pairing_code_local_invalid))
            .assertIsDisplayed()
        assertEquals(0, pairingCallCount.get())
    }

    @Test
    fun thePairingCodeIsNeverRenderedAsPlainText() {
        setContent { DevicePairingOutcome.Failure(DevicePairingFailure.InvalidInput) }

        fillPairingForm()
        assertPasswordField()
        // The raw value may exist ONLY as the (password-protected) field
        // state; no visible Text node and no content description may
        // expose it.
        composeRule
            .onAllNodes(hasText(PAIRING_CODE))
            .fetchSemanticsNodes()
            .forEach { node ->
                val visible = node.config[SemanticsProperties.Text] ?: emptyList()
                assertTrue(
                    "no visible text may contain the code",
                    visible.none { it.text.contains(PAIRING_CODE) }
                )
                if (node.config.contains(SemanticsProperties.EditableText)) {
                    assertTrue(
                        "the field holding the code must be password-protected",
                        node.config.contains(SemanticsProperties.Password)
                    )
                }
            }
    }

    private fun assertPasswordField() {
        // Pattern proven in ChatDraftPersistenceUiTest: read the merged
        // field node's configuration directly.
        val field = composeRule
            .onNodeWithContentDescription(context.getString(R.string.onboarding_pairing_code_label), useUnmergedTree = true)
            .fetchSemanticsNode()
        assertTrue(
            "the pairing code field has password semantics",
            field.config.contains(SemanticsProperties.Password)
        )
    }

    private fun setContent(
        connectPairing: () -> DevicePairingOutcome
    ) {
        composeRule.setContent {
            OnboardingRoute(
                connect = { ProfileConnectionOutcome.Failure(ConnectionFailureReason.INVALID_INPUT) },
                connectPairing = { _, _, _ ->
                    pairingCallCount.incrementAndGet()
                    connectPairing()
                },
                onConnected = { connectedProfiles += it }
            )
        }
    }

    // Synthetic fixture code: exactly 24 characters from the closed
    // pairing alphabet (A-Z without I, L, O, U plus 1-9).
    private fun fillPairingForm(code: String = PAIRING_CODE) {
        // performTextReplacement (clear + type): the failure loop re-enters
        // this helper per case against the SAME composition; plain input
        // would append and corrupt the fields.
        composeRule.onNodeWithTag("onboardingProfileName")
            .performTextReplacement("Fixture")
        composeRule.onNodeWithTag("onboardingBaseUrl")
            .performTextReplacement("https://example.invalid/p/test")
        composeRule.onNodeWithTag("onboardingPairingCode")
            .performTextReplacement(code)
    }

    private fun waitForText(text: String) {
        // substring = true: the closed messages are asserted by their
        // distinctive prefix, not by the full sentence.
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule
                .onAllNodes(hasText(text, substring = true))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.waitForIdle()
    }

    private companion object {
        const val PAIRING_CODE = "AB12CD34EF56GH78JK9MNPQR"
    }
}
