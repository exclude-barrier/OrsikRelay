package io.github.excludebarrier.orsikrelay.domain.device

import io.github.excludebarrier.orsikrelay.data.profile.PairedProfileSaver
import io.github.excludebarrier.orsikrelay.domain.connection.ConnectionFailureReason
import io.github.excludebarrier.orsikrelay.domain.connection.ConnectionProbe
import io.github.excludebarrier.orsikrelay.domain.connection.HermesConnectionResult
import io.github.excludebarrier.orsikrelay.domain.profile.ProfileBaseUrlValidator
import io.github.excludebarrier.orsikrelay.domain.profile.ProfileDraft
import io.github.excludebarrier.orsikrelay.domain.profile.ProfileFormValidator

/**
 * Owns the one-shot pairing transaction used by onboarding.
 *
 * The freshly issued key is verified against the normal authenticated
 * health/capabilities probe before it is persisted. Every post-pair failure
 * triggers exactly one best-effort self-revoke so an unreachable credential
 * is not intentionally left active. No network write is retried.
 */
class DevicePairingCoordinator(
    private val pairTransport: DevicePairTransport,
    private val connectionProbe: ConnectionProbe,
    private val pairedProfileSaver: PairedProfileSaver,
    private val selfRevokeTransport: DeviceSelfRevokeTransport
) {
    fun connect(profileName: String, baseUrl: String, pairingCode: String): DevicePairingOutcome {
        val normalizedName = profileName.trim()
        val normalizedUrl = ProfileBaseUrlValidator.validate(baseUrl).getOrNull()
        val normalizedCode = DevicePairCode.normalize(pairingCode)
        if (
            normalizedName.isEmpty() ||
            normalizedName.length > ProfileFormValidator.PROFILE_NAME_MAX_LENGTH ||
            normalizedUrl == null ||
            normalizedCode == null
        ) {
            return DevicePairingOutcome.Failure(DevicePairingFailure.InvalidInput)
        }

        return when (val pairResult = pairTransport.pairDevice(normalizedUrl, normalizedCode)) {
            is DevicePairResult.Failure ->
                DevicePairingOutcome.Failure(DevicePairingFailure.Pairing(pairResult.error))

            is DevicePairResult.Paired -> finishPairing(
                profileName = normalizedName,
                baseUrl = normalizedUrl,
                credential = pairResult.credential
            )
        }
    }

    private fun finishPairing(
        profileName: String,
        baseUrl: String,
        credential: DeviceCredential
    ): DevicePairingOutcome {
        val draft = ProfileDraft(
            profileName = profileName,
            baseUrl = baseUrl,
            apiKey = credential.deviceKey
        )
        val probe = try {
            connectionProbe.probe(draft)
        } catch (_: Exception) {
            revokeBestEffort(baseUrl, credential)
            return DevicePairingOutcome.Failure(
                DevicePairingFailure.Verification(ConnectionFailureReason.NETWORK)
            )
        }
        when (probe) {
            is HermesConnectionResult.Failure -> {
                revokeBestEffort(baseUrl, credential)
                return DevicePairingOutcome.Failure(
                    DevicePairingFailure.Verification(probe.reason)
                )
            }
            is HermesConnectionResult.Success -> {
                if (!probe.deviceCredentialsSupported) {
                    revokeBestEffort(baseUrl, credential)
                    return DevicePairingOutcome.Failure(DevicePairingFailure.IncompatiblePairing)
                }
                return try {
                    val profile = pairedProfileSaver.saveVerifiedPaired(draft, credential)
                    DevicePairingOutcome.Success(
                        version = probe.version,
                        profileId = profile.id,
                        sessionChatInteractiveApprovals =
                            probe.sessionChatInteractiveApprovals
                    )
                } catch (_: Exception) {
                    revokeBestEffort(baseUrl, credential)
                    DevicePairingOutcome.Failure(DevicePairingFailure.Persistence)
                }
            }
        }
    }

    private fun revokeBestEffort(baseUrl: String, credential: DeviceCredential) {
        try {
            selfRevokeTransport.revokeSelf(baseUrl, credential.deviceKey)
        } catch (_: Exception) {
            // The original closed failure is retained; never retry or disclose details.
        }
    }
}

sealed interface DevicePairingOutcome {
    data class Success(
        val version: String,
        val profileId: String,
        val sessionChatInteractiveApprovals: Boolean
    ) : DevicePairingOutcome

    data class Failure(val reason: DevicePairingFailure) : DevicePairingOutcome
}

sealed interface DevicePairingFailure {
    data object InvalidInput : DevicePairingFailure
    data class Pairing(val reason: DevicePairError) : DevicePairingFailure
    data class Verification(val reason: ConnectionFailureReason) : DevicePairingFailure
    data object IncompatiblePairing : DevicePairingFailure
    data object Persistence : DevicePairingFailure
}
