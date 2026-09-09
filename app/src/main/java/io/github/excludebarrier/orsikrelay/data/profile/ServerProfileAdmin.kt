package io.github.excludebarrier.orsikrelay.data.profile

import io.github.excludebarrier.orsikrelay.domain.profile.ProfileAdmin
import io.github.excludebarrier.orsikrelay.domain.profile.ServerProfile

/**
 * Production [ProfileAdmin] (Mission 010, extended in Mission 012):
 * delegates 1:1 to the existing verified [ServerProfileRepository]
 * list/delete/select path. No second deletion or selection implementation.
 */
class ServerProfileAdmin(
    private val profileRepository: ServerProfileRepository
) : ProfileAdmin {

    override fun listProfiles(): List<ServerProfile> = profileRepository.list()

    override fun deleteProfile(profileId: String) {
        profileRepository.delete(profileId)
    }

    override fun selectActiveServer(profileId: String) {
        profileRepository.selectActiveServer(profileId)
    }
}
