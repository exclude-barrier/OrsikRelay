package io.github.excludebarrier.orsikrelay.domain.device

import io.github.excludebarrier.orsikrelay.domain.profile.ProfileAdmin
import io.github.excludebarrier.orsikrelay.domain.profile.SecretValue

/**
 * One-shot orchestration of the "Gerät trennen" (disconnect) action
 * (Mission 017B).
 *
 * Flow, in this exact order:
 * 1. Load the profile's base URL and its SEALED device key through
 *    [DeviceDisconnectSource]. The key never leaves [SecretValue]: it is
 *    never displayed, shortened, or copied into a result, exception,
 *    log or state.
 * 2. Call [DeviceSelfRevokeTransport.revokeSelf] EXACTLY ONCE. No retry
 *    under any circumstance (the revocation is a non-idempotent write).
 * 3. Only when the server answered [DeviceSelfRevokeResult.Revoked] —
 *    both `revoked = true` (fresh revocation) and `revoked = false`
 *    (already revoked) — remove the local profile connection through the
 *    established, verified [ProfileAdmin.deleteProfile] path
 *    (credential, metadata, device metadata best-effort, drafts,
 *    selection).
 *
 * Every failure is a closed [DeviceDisconnectError] without raw values,
 * and a remote failure deletes NOTHING locally.
 */
class DeviceDisconnectCoordinator(
    private val disconnectSource: DeviceDisconnectSource,
    private val selfRevokeTransport: DeviceSelfRevokeTransport,
    private val profileAdmin: ProfileAdmin
) {
    fun disconnect(profileId: String): DeviceDisconnectResult {
        val target = try {
            disconnectSource.load(profileId)
        } catch (_: Exception) {
            return DeviceDisconnectResult.Failure(DeviceDisconnectError.InvalidState)
        }
        val loaded = target ?: return DeviceDisconnectResult.Failure(DeviceDisconnectError.InvalidState)

        val revokeResult = try {
            selfRevokeTransport.revokeSelf(loaded.baseUrl, loaded.deviceKey)
        } catch (_: Exception) {
            // Never retry, never disclose: a failed or exceptional
            // revocation leaves the local profile untouched.
            return DeviceDisconnectResult.Failure(DeviceDisconnectError.RevokeFailed)
        }
        if (revokeResult !is DeviceSelfRevokeResult.Revoked) {
            return DeviceDisconnectResult.Failure(DeviceDisconnectError.RevokeFailed)
        }

        return try {
            profileAdmin.deleteProfile(profileId)
            DeviceDisconnectResult.Disconnected
        } catch (_: Exception) {
            // The device key is already revoked server-side; only the
            // local cleanup failed. The closed failure keeps the UI on the
            // generic message and never exposes details.
            DeviceDisconnectResult.Failure(DeviceDisconnectError.DeletionFailed)
        }
    }
}

/**
 * Narrow, UI-free seam for the disconnect path (Mission 017B).
 *
 * Loads exactly what the revocation needs: the profile's HTTPS base URL
 * and its sealed credential. It returns null (never throws with details)
 * when the profile or its credential is absent. Implementations must not
 * return a key that is not the profile's CURRENT credential.
 */
fun interface DeviceDisconnectSource {
    fun load(profileId: String): DeviceDisconnectTarget?
}

/** The sealed inputs of one disconnect: base URL plus the device key. */
data class DeviceDisconnectTarget(
    val baseUrl: String,
    val deviceKey: SecretValue
)

/** Closed outcome of one disconnect attempt (Mission 017B). */
sealed interface DeviceDisconnectResult {
    /** Revocation acknowledged AND the local profile was removed. */
    data object Disconnected : DeviceDisconnectResult

    /** The disconnect failed; [reason] is closed and input-free. */
    data class Failure(val reason: DeviceDisconnectError) : DeviceDisconnectResult
}

/** Closed failure reasons of the disconnect path (Mission 017B). */
sealed interface DeviceDisconnectError {
    /** The profile or its sealed credential could not be loaded. */
    data object InvalidState : DeviceDisconnectError

    /** The server did not acknowledge the revocation (or the call threw). Nothing local was deleted. */
    data object RevokeFailed : DeviceDisconnectError

    /** The revocation succeeded but the local profile cleanup failed. */
    data object DeletionFailed : DeviceDisconnectError
}
