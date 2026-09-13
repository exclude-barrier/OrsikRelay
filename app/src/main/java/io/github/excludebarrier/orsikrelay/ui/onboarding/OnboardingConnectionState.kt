package io.github.excludebarrier.orsikrelay.ui.onboarding

import io.github.excludebarrier.orsikrelay.domain.connection.ConnectionFailureReason

sealed interface OnboardingConnectionState {
    data object Idle : OnboardingConnectionState
    data object Connecting : OnboardingConnectionState
    data class Success(
        val version: String,
        val profileId: String
    ) : OnboardingConnectionState
    data class Failure(
        val reason: OnboardingFailureReason
    ) : OnboardingConnectionState
}

enum class OnboardingFailureReason {
    INVALID_INPUT,
    INVALID_CREDENTIALS,
    RATE_LIMITED,
    INCOMPATIBLE_API,
    TLS,
    TIMEOUT,
    NETWORK,
    HTTP,
    PERSISTENCE,
    // Mission 017B: closed reasons of the device pairing path.
    PAIRING_CODE_INVALID,
    PAIRING_DEVICE_LIMIT,
    PAIRING_RATE_LIMITED,
    PAIRING_INVALID_REQUEST,
    PAIRING_INCOMPATIBLE,
    PAIRING_FAILED,
    PAIRING_CREDENTIAL_REJECTED
}

internal fun ConnectionFailureReason.toOnboardingFailureReason(): OnboardingFailureReason =
    when (this) {
        ConnectionFailureReason.INVALID_INPUT -> OnboardingFailureReason.INVALID_INPUT
        ConnectionFailureReason.INVALID_CREDENTIALS -> OnboardingFailureReason.INVALID_CREDENTIALS
        ConnectionFailureReason.RATE_LIMITED -> OnboardingFailureReason.RATE_LIMITED
        ConnectionFailureReason.INCOMPATIBLE_API -> OnboardingFailureReason.INCOMPATIBLE_API
        ConnectionFailureReason.TLS -> OnboardingFailureReason.TLS
        ConnectionFailureReason.TIMEOUT -> OnboardingFailureReason.TIMEOUT
        ConnectionFailureReason.NETWORK -> OnboardingFailureReason.NETWORK
        ConnectionFailureReason.HTTP -> OnboardingFailureReason.HTTP
    }
