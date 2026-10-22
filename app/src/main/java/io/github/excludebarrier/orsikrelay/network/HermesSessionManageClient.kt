package io.github.excludebarrier.orsikrelay.network

import io.github.excludebarrier.orsikrelay.domain.profile.ProfileBaseUrlValidator
import io.github.excludebarrier.orsikrelay.domain.profile.SecretValue
import io.github.excludebarrier.orsikrelay.domain.session.SessionManageError
import io.github.excludebarrier.orsikrelay.domain.session.SessionManageResult
import io.github.excludebarrier.orsikrelay.domain.session.SessionManageTransport
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Chat management against the bridge: the capability read
 * (`GET /v1/capabilities`), archive (`PATCH /api/sessions/{id}` with exactly
 * `{"archived": bool}`) and delete (`DELETE /api/sessions/{id}`, no body).
 *
 * HTTPS only, no redirects, no connection retry, the writes are never
 * repeated, bounded bodies, and closed results: no key, URL, ID or body
 * text reaches a result.
 */
class HermesSessionManageClient(
    okHttpClient: OkHttpClient = HermesHttp.defaultClient()
) : SessionManageTransport {

    private val client = okHttpClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()

    override fun supportsManagement(baseUrl: String, apiKey: SecretValue): Boolean {
        val base = baseUrl(baseUrl, apiKey) ?: return false
        val url = base.newBuilder().addPathSegment("v1").addPathSegment("capabilities").build()
        return try {
            client.newCall(request(url, apiKey).get().build()).execute().use { response ->
                if (response.code != 200) return false
                val text = readBoundedUtf8(response.body, MAX_CAPABILITIES_BYTES) { return false }
                advertisesManagement(JSONObject(text))
            }
        } catch (_: Exception) {
            false
        }
    }

    override fun setArchived(
        baseUrl: String,
        apiKey: SecretValue,
        sessionId: String,
        archived: Boolean
    ): SessionManageResult {
        val url = sessionUrl(baseUrl, apiKey, sessionId) ?: return failed()
        val body = JSONObject().put(ARCHIVED_FIELD, archived).toString().toByteArray().toRequestBody(JSON)
        return write(request(url, apiKey).patch(body).build(), SESSION_OBJECT)
    }

    override fun delete(baseUrl: String, apiKey: SecretValue, sessionId: String): SessionManageResult {
        val url = sessionUrl(baseUrl, apiKey, sessionId) ?: return failed()
        return write(request(url, apiKey).delete().build(), DELETED_OBJECT)
    }

    private fun write(request: Request, expectedObject: String): SessionManageResult = try {
        client.newCall(request).execute().use { response ->
            when (response.code) {
                200 -> {
                    val text = readBoundedUtf8(response.body, MAX_RESPONSE_BYTES) { return failed() }
                    val root = JSONObject(text)
                    when {
                        root.opt(OBJECT_FIELD) != expectedObject -> failed()
                        // The row vanished between the check and the delete.
                        expectedObject == DELETED_OBJECT && root.opt(DELETED_FIELD) != true ->
                            SessionManageResult.Failure(SessionManageError.NotFound)
                        else -> SessionManageResult.Success
                    }
                }
                404 -> SessionManageResult.Failure(SessionManageError.NotFound)
                409 -> SessionManageResult.Failure(SessionManageError.ActiveTurn)
                else -> failed()
            }
        }
    } catch (_: Exception) {
        failed()
    }

    private fun advertisesManagement(root: JSONObject): Boolean {
        val features = root.optJSONObject("features") ?: return false
        val endpoints = root.optJSONObject("endpoints") ?: return false
        return features.opt("session_archive") == true &&
            features.opt("session_delete") == true &&
            endpoints.isEndpoint("session_update", "PATCH") &&
            endpoints.isEndpoint("session_delete", "DELETE")
    }

    private fun JSONObject.isEndpoint(key: String, method: String): Boolean {
        val endpoint = optJSONObject(key) ?: return false
        return endpoint.opt("method") == method && endpoint.opt("path") == SESSION_PATH
    }

    private fun baseUrl(baseUrl: String, apiKey: SecretValue): HttpUrl? {
        if (!isSafeApiKey(apiKey)) return null
        return ProfileBaseUrlValidator.validate(baseUrl).getOrNull()?.toHttpUrl()
    }

    private fun sessionUrl(baseUrl: String, apiKey: SecretValue, sessionId: String): HttpUrl? {
        if (!isSafeOpaqueId(sessionId)) return null
        return baseUrl(baseUrl, apiKey)?.newBuilder()
            ?.addPathSegment("api")
            ?.addPathSegment("sessions")
            ?.addPathSegment(sessionId)
            ?.build()
    }

    private fun request(url: HttpUrl, apiKey: SecretValue): Request.Builder =
        apiKey.useRaw { rawKey -> Request.Builder().url(url).header("Authorization", "Bearer $rawKey") }

    private fun failed() = SessionManageResult.Failure(SessionManageError.Failed)

    private companion object {
        const val MAX_CAPABILITIES_BYTES = 256 * 1024
        const val MAX_RESPONSE_BYTES = 512 * 1024
        const val SESSION_PATH = "/api/sessions/{session_id}"
        const val OBJECT_FIELD = "object"
        const val ARCHIVED_FIELD = "archived"
        const val DELETED_FIELD = "deleted"
        const val SESSION_OBJECT = "hermes.session"
        const val DELETED_OBJECT = "hermes.session.deleted"
        val JSON = "application/json".toMediaType()
    }
}
