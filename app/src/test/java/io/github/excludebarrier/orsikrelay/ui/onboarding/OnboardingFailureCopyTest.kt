package io.github.excludebarrier.orsikrelay.ui.onboarding

import io.github.excludebarrier.orsikrelay.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The closed mapping from failure reason to the notice title and its single
 * action sentence.
 *
 * Every reason is covered with both resources, so a newly added reason makes
 * this test fail instead of silently rendering no card.
 */
class OnboardingFailureCopyTest {

    @Test
    fun everyReasonMapsToADistinctTitleAndText() {
        val expected = mapOf(
            OnboardingFailureReason.INVALID_INPUT to
                (R.string.onboarding_error_invalid_input_title to
                    R.string.onboarding_error_invalid_input_text),
            OnboardingFailureReason.INVALID_CREDENTIALS to
                (R.string.onboarding_error_invalid_credentials_title to
                    R.string.onboarding_error_invalid_credentials_text),
            OnboardingFailureReason.RATE_LIMITED to
                (R.string.onboarding_error_rate_limited_title to
                    R.string.onboarding_error_rate_limited_text),
            OnboardingFailureReason.INCOMPATIBLE_API to
                (R.string.onboarding_error_incompatible_api_title to
                    R.string.onboarding_error_incompatible_api_text),
            OnboardingFailureReason.TLS to
                (R.string.onboarding_error_tls_title to R.string.onboarding_error_tls_text),
            OnboardingFailureReason.TIMEOUT to
                (R.string.onboarding_error_timeout_title to R.string.onboarding_error_timeout_text),
            OnboardingFailureReason.NETWORK to
                (R.string.onboarding_error_network_title to R.string.onboarding_error_network_text),
            OnboardingFailureReason.HTTP to
                (R.string.onboarding_error_http_title to R.string.onboarding_error_http_text),
            OnboardingFailureReason.PERSISTENCE to
                (R.string.onboarding_error_persistence_title to R.string.onboarding_error_persistence_text),
            OnboardingFailureReason.PAIRING_CODE_INVALID to
                (R.string.onboarding_error_pairing_code_invalid_title to
                    R.string.onboarding_error_pairing_code_invalid_text),
            OnboardingFailureReason.PAIRING_DEVICE_LIMIT to
                (R.string.onboarding_error_pairing_device_limit_title to
                    R.string.onboarding_error_pairing_device_limit_text),
            OnboardingFailureReason.PAIRING_RATE_LIMITED to
                (R.string.onboarding_error_pairing_rate_limited_title to
                    R.string.onboarding_error_pairing_rate_limited_text),
            OnboardingFailureReason.PAIRING_INVALID_REQUEST to
                (R.string.onboarding_error_pairing_invalid_request_title to
                    R.string.onboarding_error_pairing_invalid_request_text),
            OnboardingFailureReason.PAIRING_INCOMPATIBLE to
                (R.string.onboarding_error_pairing_incompatible_title to
                    R.string.onboarding_error_pairing_incompatible_text),
            OnboardingFailureReason.PAIRING_FAILED to
                (R.string.onboarding_error_pairing_failed_title to
                    R.string.onboarding_error_pairing_failed_text),
            OnboardingFailureReason.PAIRING_CREDENTIAL_REJECTED to
                (R.string.onboarding_error_pairing_credential_rejected_title to
                    R.string.onboarding_error_pairing_credential_rejected_text)
        )

        assertEquals(
            "every failure reason must be covered",
            OnboardingFailureReason.entries.toSet(),
            expected.keys
        )
        expected.forEach { (reason, resources) ->
            assertEquals("unexpected copy for $reason", resources, onboardingFailureCopy(reason))
            assertNotEquals(
                "title and text of $reason must be different resources",
                resources.first,
                resources.second
            )
        }
    }
}
