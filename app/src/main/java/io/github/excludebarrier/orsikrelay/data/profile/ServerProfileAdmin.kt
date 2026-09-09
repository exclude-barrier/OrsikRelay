package io.github.excludebarrier.orsikrelay.data.profile

import io.github.excludebarrier.orsikrelay.domain.profile.ProfileAdmin
import io.github.excludebarrier.orsikrelay.domain.profile.ServerProfile

/**
 * Production [ProfileAdmin] (Mission 010): delegates 1:1 to the existing
 * verified [ServerProfileRepository] list/delete path. No second deletion
 * implementation.
 */
class ServerProfileAdmin(
    private val profileRepository: ServerProfileRepository
) : ProfileAdmin {

    override fun listProfiles(): List<ServerProfile> = profileRepository.list()

    override fun deleteProfile(profileId: String) {
        profileRepository.delete(profileId)
    }
}
