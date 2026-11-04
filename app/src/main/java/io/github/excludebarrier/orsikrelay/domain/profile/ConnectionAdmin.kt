package io.github.excludebarrier.orsikrelay.domain.profile

import kotlinx.coroutines.flow.StateFlow

/** The path a profile's calls currently take. */
enum class ConnectionRoute { PRIVATE, PUBLIC }

/** UI seam for a profile's two endpoints. */
interface ConnectionAdmin {
    /** Current route per profile id (absent until the first call). */
    val routes: StateFlow<Map<String, ConnectionRoute>>

    /**
     * Saves [settings] for [profileId] (metadata only) and forgets every
     * cached endpoint choice. Blocking; call it off the main thread.
     */
    fun save(profileId: String, settings: ConnectionSettings)
}
