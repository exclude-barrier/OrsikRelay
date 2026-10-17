package io.github.excludebarrier.orsikrelay.ui.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import io.github.excludebarrier.orsikrelay.domain.connection.ConnectionFailureReason
import io.github.excludebarrier.orsikrelay.domain.connection.ProfileConnectionOutcome
import io.github.excludebarrier.orsikrelay.domain.device.DevicePairError
import io.github.excludebarrier.orsikrelay.domain.device.DevicePairingFailure
import io.github.excludebarrier.orsikrelay.domain.device.DevicePairingOutcome
import io.github.excludebarrier.orsikrelay.domain.device.PairingBootstrap
import io.github.excludebarrier.orsikrelay.domain.profile.ProfileDraft
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Onboarding navigation route.
 *
 * Both modes converge on the same [onConnected] callback with a
 * [ProfileConnectionOutcome.Success], so a freshly paired device and a
 * freshly connected API-key profile both become the active server
 * through the identical, verified navigation path.
 *
 * - [connect] is the pre-existing API-key path (probe + save), unchanged.
 * - [connectPairing] is the pairing path: unauthenticated
 *   pair POST, authenticated probe with strict device-credential
 *   capability confirmation, exactly one best-effort self-revoke on any
 *   post-pair failure, and persistence only after verification. It must
 *   be safe to call from `Dispatchers.IO` and never leak the pairing
 *   code or the device key.
 */
@Composable
fun OnboardingRoute(
    connect: (ProfileDraft) -> ProfileConnectionOutcome,
    // The default (pairing mode) fails closed when a test or
    // a legacy host does not wire a pairing coordinator.
    connectPairing: (profileName: String, baseUrl: String, pairingCode: String) -> DevicePairingOutcome = { _, _, _ ->
        DevicePairingOutcome.Failure(DevicePairingFailure.InvalidInput)
    },
    onConnected: (ProfileConnectionOutcome.Success) -> Unit,
    // Product Gate: a pairing deep-link bootstrap (server URL + code) pre-fills
    // the pairing fields so the user never types a base URL or API key.
    pairingBootstrap: PairingBootstrap? = null
) {
    val coroutineScope = rememberCoroutineScope()
    var connectionState by remember {
        mutableStateOf<OnboardingConnectionState>(OnboardingConnectionState.Idle)
    }

    OnboardingScreen(
        initialProfileName = pairingBootstrap?.suggestedProfileName.orEmpty(),
        initialBaseUrl = pairingBootstrap?.baseUrl.orEmpty(),
        initialPairingCode = pairingBootstrap?.pairingCode.orEmpty(),
        connectionState = connectionState,
        onInputChanged = {
            if (connectionState is OnboardingConnectionState.Failure) {
                connectionState = OnboardingConnectionState.Idle
            }
        },
        onValidDraft = { draft ->
            if (connectionState !is OnboardingConnectionState.Connecting) {
                connectionState = OnboardingConnectionState.Connecting
                coroutineScope.launch {
                    val outcome = try {
                        withContext(Dispatchers.IO) { connect(draft) }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        ProfileConnectionOutcome.Failure(ConnectionFailureReason.NETWORK)
                    }
                    when (outcome) {
                        is ProfileConnectionOutcome.Success -> {
                            connectionState = OnboardingConnectionState.Success(
                                version = outcome.version,
                                profileId = outcome.profileId
                            )
                            onConnected(outcome)
                        }

                        is ProfileConnectionOutcome.Failure -> {
                            connectionState = OnboardingConnectionState.Failure(
                                outcome.reason.toOnboardingFailureReason()
                            )
                        }

                        ProfileConnectionOutcome.PersistenceFailure -> {
                            connectionState = OnboardingConnectionState.Failure(
                                OnboardingFailureReason.PERSISTENCE
                            )
                        }
                    }
                }
            }
        },
        onValidPairing = { profileName, baseUrl, pairingCode ->
            if (connectionState !is OnboardingConnectionState.Connecting) {
                connectionState = OnboardingConnectionState.Connecting
                coroutineScope.launch {
                    val outcome = try {
                        withContext(Dispatchers.IO) {
                            connectPairing(profileName, baseUrl, pairingCode)
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        DevicePairingOutcome.Failure(DevicePairingFailure.Verification(
                            ConnectionFailureReason.NETWORK
                        ))
                    }
                    when (outcome) {
                        is DevicePairingOutcome.Success -> {
                            // Same verified navigation path as the
                            // API-key mode: the success outcome carries the
                            // probe version, the new profile ID and the
                            // approval capability of the paired profile.
                            val success = ProfileConnectionOutcome.Success(
                                version = outcome.version,
                                profileId = outcome.profileId,
                                sessionChatInteractiveApprovals = outcome.sessionChatInteractiveApprovals
                            )
                            connectionState = OnboardingConnectionState.Success(
                                version = outcome.version,
                                profileId = outcome.profileId
                            )
                            onConnected(success)
                        }

                        is DevicePairingOutcome.Failure -> {
                            connectionState = OnboardingConnectionState.Failure(
                                outcome.reason.toOnboardingFailureReason()
                            )
                        }
                    }
                }
            }
        }
    )
}

internal fun DevicePairingFailure.toOnboardingFailureReason(): OnboardingFailureReason = when (this) {
    DevicePairingFailure.InvalidInput -> OnboardingFailureReason.INVALID_INPUT
    is DevicePairingFailure.Pairing -> when (reason) {
        DevicePairError.InvalidInput -> OnboardingFailureReason.INVALID_INPUT
        DevicePairError.PairingCodeInvalid -> OnboardingFailureReason.PAIRING_CODE_INVALID
        DevicePairError.InvalidPairRequest -> OnboardingFailureReason.PAIRING_INVALID_REQUEST
        DevicePairError.DeviceLimitExceeded -> OnboardingFailureReason.PAIRING_DEVICE_LIMIT
        DevicePairError.PairRateLimited -> OnboardingFailureReason.PAIRING_RATE_LIMITED
        DevicePairError.PairFailed -> OnboardingFailureReason.PAIRING_FAILED
        DevicePairError.InvalidCredentials -> OnboardingFailureReason.PAIRING_CREDENTIAL_REJECTED
        DevicePairError.NotFound -> OnboardingFailureReason.PAIRING_INCOMPATIBLE
        DevicePairError.Tls -> OnboardingFailureReason.TLS
        DevicePairError.Timeout -> OnboardingFailureReason.TIMEOUT
        DevicePairError.Network -> OnboardingFailureReason.NETWORK
        DevicePairError.Http -> OnboardingFailureReason.HTTP
        DevicePairError.IncompatibleApi -> OnboardingFailureReason.PAIRING_INCOMPATIBLE
    }
    // The authenticated probe failed after a fresh credential was issued:
    // the closed probe reasons are shown, but an explicit credential
    // rejection gets its own pairing-specific message.
    is DevicePairingFailure.Verification -> reason.toOnboardingFailureReason().let {
        if (it == OnboardingFailureReason.INVALID_CREDENTIALS) {
            OnboardingFailureReason.PAIRING_CREDENTIAL_REJECTED
        } else {
            it
        }
    }
    // The authenticated probe succeeded but the strict
    // device-credential capability was missing or malformed.
    DevicePairingFailure.IncompatiblePairing -> OnboardingFailureReason.PAIRING_INCOMPATIBLE
    DevicePairingFailure.Persistence -> OnboardingFailureReason.PERSISTENCE
}
