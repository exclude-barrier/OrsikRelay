package io.github.excludebarrier.orsikrelay.data.connection

import io.github.excludebarrier.orsikrelay.data.profile.ServerProfileRepository
import io.github.excludebarrier.orsikrelay.domain.profile.ConnectionAdmin
import io.github.excludebarrier.orsikrelay.domain.profile.ConnectionRoute
import io.github.excludebarrier.orsikrelay.domain.profile.ConnectionSettings
import kotlinx.coroutines.flow.StateFlow

/** Production [ConnectionAdmin]: profile metadata plus the resolver's choices. */
class ServerConnectionAdmin(
    private val profileRepository: ServerProfileRepository,
    private val resolver: EndpointResolver
) : ConnectionAdmin {

    override val routes: StateFlow<Map<String, ConnectionRoute>> = resolver.routes

    override fun save(profileId: String, settings: ConnectionSettings) {
        profileRepository.updateConnection(profileId, settings)
        resolver.invalidate()
    }
}
