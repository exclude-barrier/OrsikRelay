package io.github.excludebarrier.orsikrelay.data.profile

import io.github.excludebarrier.orsikrelay.domain.device.DeviceCredentialKey
import io.github.excludebarrier.orsikrelay.domain.device.DeviceDisconnectSource
import io.github.excludebarrier.orsikrelay.domain.device.DeviceDisconnectTarget
import io.github.excludebarrier.orsikrelay.domain.profile.ProfileAdmin
import io.github.excludebarrier.orsikrelay.domain.profile.ProfileCredentialKind
import io.github.excludebarrier.orsikrelay.domain.profile.ServerProfile

/**
 * Production [ProfileAdmin]:
 * - delegates 1:1 to the existing verified [ServerProfileRepository]
 *   list/delete/select path — no second deletion or selection
 *   implementation;
 * - [credentialKind] derives the non-secret credential
 *   kind from the repository's device-metadata probe. It reads NO
 *   credential: the device key stays sealed in the credential store.
 * - as a [DeviceDisconnectSource] it loads the profile's
 *   base URL plus its SEALED credential for the disconnect path. The
 *   returned [io.github.excludebarrier.orsikrelay.domain.profile.SecretValue] is never
 *   displayed, shortened or copied anywhere; a missing profile or
 *   credential reports null.
 */
class ServerProfileAdmin(
    private val profileRepository: ServerProfileRepository
) : ProfileAdmin, DeviceDisconnectSource {

    override fun listProfiles(): List<ServerProfile> = profileRepository.list()

    override fun deleteProfile(profileId: String) {
        profileRepository.delete(profileId)
    }

    override fun selectActiveServer(profileId: String) {
        profileRepository.selectActiveServer(profileId)
    }

    override fun credentialKind(profileId: String): ProfileCredentialKind =
        if (profileRepository.hasDeviceCredential(profileId)) {
            ProfileCredentialKind.DEVICE_CREDENTIAL
        } else {
            ProfileCredentialKind.API_KEY
        }

    override fun deviceId(profileId: String): String? = profileRepository.deviceId(profileId)

    override fun load(profileId: String): DeviceDisconnectTarget? {
        return try {
            // Defense in depth: the UI hides disconnect for API-key profiles,
            // but the production source re-checks non-secret device metadata
            // so a direct coordinator call can never submit an API key to
            // self-revoke.
            if (!profileRepository.hasDeviceCredential(profileId)) return null
            val profile = profileRepository.get(profileId) ?: return null
            val credential = profileRepository.credentialFor(profileId) ?: return null
            if (!DeviceCredentialKey.isValid(credential)) return null
            DeviceDisconnectTarget(
                baseUrl = profile.baseUrl,
                deviceKey = credential
            )
        } catch (_: Exception) {
            null
        }
    }
}
