package io.github.excludebarrier.orsikrelay.data.profile

import io.github.excludebarrier.orsikrelay.domain.profile.ServerProfile

interface ServerProfileMetadataStore {
    fun save(profile: ServerProfile)
    fun get(profileId: String): ServerProfile?
    fun list(): List<ServerProfile>
    fun delete(profileId: String)
}
