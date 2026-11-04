package io.github.excludebarrier.orsikrelay.data.connection

import io.github.excludebarrier.orsikrelay.domain.profile.ConnectionEndpoints
import io.github.excludebarrier.orsikrelay.domain.profile.ConnectionMode
import io.github.excludebarrier.orsikrelay.domain.profile.ConnectionRoute
import io.github.excludebarrier.orsikrelay.domain.profile.ServerProfile
import io.github.excludebarrier.orsikrelay.network.ServerIdentityProbe
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Chooses between a profile's paired (private) endpoint and its public one.
 *
 * The device key goes only to:
 * - the paired base URL, or
 * - the public base URL after `GET /v1/server` there reported exactly the
 *   server id pinned from the paired endpoint.
 * Anything else (no pin yet, a mismatch, no answer) falls back to the paired
 * endpoint, so a misconfigured or foreign public host never sees the key.
 *
 * [AUTOMATIC][ConnectionMode.AUTOMATIC] prefers the paired endpoint whenever
 * it answers. A choice is kept for [maxAgeMillis] or until [invalidate]
 * (network change, edited settings). The public host is bound to the
 * profile's KeyChain alias for mTLS before it is contacted.
 */
class EndpointResolver(
    private val probe: (String) -> ServerIdentityProbe,
    private val pin: (profileId: String, serverId: String) -> Unit,
    private val bindClientCertificate: (host: String, alias: String?) -> Unit,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val maxAgeMillis: Long = 60_000
) : ConnectionEndpoints {

    private data class Choice(val url: String, val route: ConnectionRoute, val at: Long, val settingsKey: Any)

    private val choices = ConcurrentHashMap<String, Choice>()
    private val routeState = MutableStateFlow<Map<String, ConnectionRoute>>(emptyMap())

    /** Current route per profile id, for the UI. */
    val routes: StateFlow<Map<String, ConnectionRoute>> = routeState.asStateFlow()

    fun invalidate() {
        choices.clear()
    }

    override fun baseUrlFor(profile: ServerProfile): String {
        val connection = profile.connection
        val publicUrl = connection.publicBaseUrl
        if (connection.mode == ConnectionMode.PRIVATE_ONLY || publicUrl == null) {
            remember(profile, profile.baseUrl, ConnectionRoute.PRIVATE)
            return profile.baseUrl
        }
        choices[profile.id]
            ?.takeIf { it.settingsKey == settingsKey(profile) && nowMillis() - it.at < maxAgeMillis }
            ?.let { return it.url }
        var pinned = connection.serverId
        if (connection.mode == ConnectionMode.AUTOMATIC || pinned == null) {
            val private = probe(profile.baseUrl)
            if (private is ServerIdentityProbe.Reachable) {
                if (pinned == null && private.serverId != null) {
                    pinned = private.serverId
                    runCatching { pin(profile.id, private.serverId) }
                }
                if (connection.mode == ConnectionMode.AUTOMATIC) {
                    return remember(profile, profile.baseUrl, ConnectionRoute.PRIVATE)
                }
            }
        }
        val host = runCatching { URI(publicUrl).host }.getOrNull()
        if (pinned != null && host != null) {
            bindClientCertificate(host, connection.clientCertAlias)
            val public = probe(publicUrl)
            if (public is ServerIdentityProbe.Reachable && public.serverId == pinned) {
                return remember(profile, publicUrl, ConnectionRoute.PUBLIC)
            }
        }
        return remember(profile, profile.baseUrl, ConnectionRoute.PRIVATE)
    }

    private fun settingsKey(profile: ServerProfile): Any = profile.baseUrl to profile.connection

    private fun remember(profile: ServerProfile, url: String, route: ConnectionRoute): String {
        choices[profile.id] = Choice(url, route, nowMillis(), settingsKey(profile))
        routeState.update { it + (profile.id to route) }
        return url
    }
}
