package io.github.excludebarrier.orsikrelay.ui.onboarding

import io.github.excludebarrier.orsikrelay.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The closed mapping from failure reason to the optional action hint.
 *
 * Every reason is covered, so a newly added reason makes this test fail
 * instead of silently rendering no hint.
 */
class OnboardingFailureHintTest {

    @Test
    fun everyReasonMapsToItsHintOrToNoHint() {
        val expected = mapOf(
            OnboardingFailureReason.INVALID_INPUT to null,
            OnboardingFailureReason.INVALID_CREDENTIALS to R.string.onboarding_hint_invalid_credentials,
            OnboardingFailureReason.RATE_LIMITED to null,
            OnboardingFailureReason.INCOMPATIBLE_API to R.string.onboarding_hint_incompatible_api,
            OnboardingFailureReason.TLS to R.string.onboarding_hint_tls,
            OnboardingFailureReason.TIMEOUT to R.string.onboarding_hint_network,
            OnboardingFailureReason.NETWORK to R.string.onboarding_hint_network,
            OnboardingFailureReason.HTTP to null,
            OnboardingFailureReason.PERSISTENCE to null,
            OnboardingFailureReason.PAIRING_CODE_INVALID to R.string.onboarding_hint_pairing_code_invalid,
            OnboardingFailureReason.PAIRING_DEVICE_LIMIT to null,
            OnboardingFailureReason.PAIRING_RATE_LIMITED to null,
            OnboardingFailureReason.PAIRING_INVALID_REQUEST to null,
            OnboardingFailureReason.PAIRING_INCOMPATIBLE to R.string.onboarding_hint_incompatible_api,
            OnboardingFailureReason.PAIRING_FAILED to null,
            OnboardingFailureReason.PAIRING_CREDENTIAL_REJECTED to null
        )

        assertEquals(
            "every failure reason must be covered",
            OnboardingFailureReason.entries.toSet(),
            expected.keys
        )
        expected.forEach { (reason, hint) ->
            if (hint == null) {
                assertNull("no hint expected for $reason", onboardingFailureHint(reason))
            } else {
                assertEquals("unexpected hint for $reason", hint, onboardingFailureHint(reason))
            }
        }
    }
}
