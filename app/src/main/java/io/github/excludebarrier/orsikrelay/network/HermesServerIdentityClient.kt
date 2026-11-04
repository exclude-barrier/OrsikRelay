package io.github.excludebarrier.orsikrelay.network

import io.github.excludebarrier.orsikrelay.domain.profile.ConnectionSettings
import io.github.excludebarrier.orsikrelay.domain.profile.ProfileBaseUrlValidator
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** Outcome of asking an endpoint for the bridge's identity. */
sealed interface ServerIdentityProbe {
    /** The endpoint answered over HTTPS; [serverId] is null for a bridge without the route. */
    data class Reachable(val serverId: String?) : ServerIdentityProbe

    /** No HTTP answer (offline, no VPN, TLS refused, timeout). */
    data object Unreachable : ServerIdentityProbe
}

/**
 * `GET /v1/server` WITHOUT any key: the bridge's random, stable server id.
 * The app compares it with the id pinned from the paired endpoint before it
 * sends the device key to another endpoint. Short timeouts, no redirects,
 * strict two-field shape.
 */
class HermesServerIdentityClient(
    okHttpClient: OkHttpClient = HermesHttp.defaultClient(readTimeoutSeconds = 4, callTimeoutSeconds = 5)
) {
    private val client = okHttpClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    fun probe(baseUrl: String): ServerIdentityProbe {
        val url = ProfileBaseUrlValidator.validate(baseUrl).getOrNull()?.toHttpUrl()
            ?.newBuilder()?.addPathSegment("v1")?.addPathSegment("server")?.build()
            ?: return ServerIdentityProbe.Unreachable
        return try {
            client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                if (response.code != 200) return ServerIdentityProbe.Reachable(null)
                requireJsonContentType(response) { return ServerIdentityProbe.Reachable(null) }
                val text = readBoundedUtf8(response.body, MAX_BYTES) { return ServerIdentityProbe.Reachable(null) }
                ServerIdentityProbe.Reachable(parse(text))
            }
        } catch (_: Exception) {
            ServerIdentityProbe.Unreachable
        }
    }

    private fun parse(text: String): String? = try {
        val root = JSONObject(text)
        val id = root.opt(SERVER_ID_FIELD) as? String
        if (root.length() == 2 && root.opt(OBJECT_FIELD) == OBJECT &&
            id != null && ConnectionSettings.isValidServerId(id)
        ) {
            id
        } else {
            null
        }
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val MAX_BYTES = 1024
        const val OBJECT = "orsik.server.identity"
        const val OBJECT_FIELD = "object"
        const val SERVER_ID_FIELD = "server_id"
    }
}
