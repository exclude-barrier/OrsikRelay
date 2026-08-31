package io.github.excludebarrier.orsikrelay.network

import io.github.excludebarrier.orsikrelay.domain.connection.ConnectionFailureReason
import io.github.excludebarrier.orsikrelay.domain.connection.HermesConnectionResult
import io.github.excludebarrier.orsikrelay.domain.profile.ProfileDraft
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import org.json.JSONException
import org.json.JSONObject

class HermesConnectionClient(
    okHttpClient: OkHttpClient = defaultHttpClient()
) {
    private val client = okHttpClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()

    fun probe(draft: ProfileDraft): HermesConnectionResult {
        val apiKeyIsSafe = draft.apiKey.useRaw { rawKey ->
            rawKey.length in 1..MAX_API_KEY_CHARACTERS &&
                rawKey.none { character ->
                    character.isWhitespace() || character.isISOControl()
                }
        }
        if (!apiKeyIsSafe) {
            return failure(ConnectionFailureReason.INVALID_INPUT)
        }
        val baseUrl = parseSecureBaseUrl(draft.baseUrl)
            ?: return failure(ConnectionFailureReason.INVALID_INPUT)

        return try {
            val health = requestJson(
                url = baseUrl.newBuilder().addPathSegment(HEALTH_SEGMENT).build(),
                draft = draft
            )
            val version = parseHealth(health)
            val capabilities = requestJson(
                url = baseUrl.newBuilder()
                    .addPathSegment(V1_SEGMENT)
                    .addPathSegment(CAPABILITIES_SEGMENT)
                    .build(),
                draft = draft
            )
            parseCapabilities(capabilities)
            HermesConnectionResult.Success(
                version = version,
                sessionsSupported = true,
                sessionStreamingSupported = true
            )
        } catch (error: ProbeFailure) {
            failure(error.reason)
        } catch (_: SocketTimeoutException) {
            failure(ConnectionFailureReason.TIMEOUT)
        } catch (_: SSLException) {
            failure(ConnectionFailureReason.TLS)
        } catch (_: InterruptedIOException) {
            failure(ConnectionFailureReason.TIMEOUT)
        } catch (_: IOException) {
            failure(ConnectionFailureReason.NETWORK)
        } catch (_: Exception) {
            failure(ConnectionFailureReason.INCOMPATIBLE_API)
        }
    }

    private fun parseSecureBaseUrl(value: String): HttpUrl? {
        val parsed = value.toHttpUrlOrNull() ?: return null
        if (
            parsed.scheme != HTTPS_SCHEME ||
            parsed.host.isBlank() ||
            parsed.username.isNotEmpty() ||
            parsed.password.isNotEmpty() ||
            parsed.query != null ||
            parsed.fragment != null
        ) {
            return null
        }
        return parsed
    }

    private fun requestJson(url: HttpUrl, draft: ProfileDraft): JSONObject {
        val request = draft.apiKey.useRaw { rawKey ->
            Request.Builder()
                .url(url)
                .get()
                .header(AUTHORIZATION_HEADER, "$BEARER_PREFIX$rawKey")
                .build()
        }
        return client.newCall(request).execute().use { response ->
            requireSuccessful(response)
            val text = readBoundedUtf8(response.body)
            try {
                JSONObject(text)
            } catch (_: JSONException) {
                throw ProbeFailure(ConnectionFailureReason.INCOMPATIBLE_API)
            }
        }
    }

    private fun requireSuccessful(response: Response) {
        when (response.code) {
            in 200..299 -> return
            401, 403 -> throw ProbeFailure(ConnectionFailureReason.INVALID_CREDENTIALS)
            429 -> throw ProbeFailure(ConnectionFailureReason.RATE_LIMITED)
            else -> throw ProbeFailure(ConnectionFailureReason.HTTP)
        }
    }

    private fun readBoundedUtf8(body: ResponseBody?): String {
        if (body == null || body.contentLength() > MAX_RESPONSE_BYTES) {
            throw ProbeFailure(ConnectionFailureReason.INCOMPATIBLE_API)
        }
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_SIZE)
        body.byteStream().use { input ->
            var total = 0
            while (true) {
                val remaining = MAX_RESPONSE_BYTES + 1 - total
                if (remaining <= 0) {
                    throw ProbeFailure(ConnectionFailureReason.INCOMPATIBLE_API)
                }
                val read = input.read(buffer, 0, minOf(buffer.size, remaining))
                if (read == -1) break
                output.write(buffer, 0, read)
                total += read
                if (total > MAX_RESPONSE_BYTES) {
                    throw ProbeFailure(ConnectionFailureReason.INCOMPATIBLE_API)
                }
            }
        }
        val bytes = output.toByteArray()
        return try {
            String(bytes, Charsets.UTF_8)
        } finally {
            bytes.fill(0)
            buffer.fill(0)
        }
    }

    private fun parseHealth(json: JSONObject): String {
        if (
            strictString(json, STATUS_FIELD) != HEALTH_OK ||
            strictString(json, PLATFORM_FIELD) != HERMES_PLATFORM
        ) {
            throw ProbeFailure(ConnectionFailureReason.INCOMPATIBLE_API)
        }
        return strictString(json, VERSION_FIELD).takeIf { it.isNotBlank() }
            ?: throw ProbeFailure(ConnectionFailureReason.INCOMPATIBLE_API)
    }

    private fun parseCapabilities(json: JSONObject) {
        if (
            strictString(json, OBJECT_FIELD) != CAPABILITIES_OBJECT ||
            strictString(json, PLATFORM_FIELD) != HERMES_PLATFORM
        ) {
            throw ProbeFailure(ConnectionFailureReason.INCOMPATIBLE_API)
        }
        val auth = strictObject(json, AUTH_FIELD)
        val runtime = strictObject(json, RUNTIME_FIELD)
        val features = strictObject(json, FEATURES_FIELD)
        val endpoints = strictObject(json, ENDPOINTS_FIELD)
        if (
            strictString(auth, TYPE_FIELD) != BEARER_TYPE ||
            !strictBoolean(auth, REQUIRED_FIELD) ||
            strictString(runtime, MODE_FIELD) != SERVER_AGENT_MODE ||
            strictString(runtime, TOOL_EXECUTION_FIELD) != SERVER_TOOL_EXECUTION ||
            !strictBoolean(features, SESSION_RESOURCES_FIELD) ||
            !strictBoolean(features, SESSION_CHAT_STREAMING_FIELD)
        ) {
            throw ProbeFailure(ConnectionFailureReason.INCOMPATIBLE_API)
        }
        validateEndpoint(endpoints, HEALTH_ENDPOINT, GET_METHOD, HEALTH_PATH)
        validateEndpoint(endpoints, SESSIONS_ENDPOINT, GET_METHOD, SESSIONS_PATH)
        validateEndpoint(
            endpoints,
            SESSION_CHAT_STREAM_ENDPOINT,
            POST_METHOD,
            SESSION_CHAT_STREAM_PATH
        )
    }

    private fun validateEndpoint(
        endpoints: JSONObject,
        key: String,
        expectedMethod: String,
        expectedPath: String
    ) {
        val endpoint = strictObject(endpoints, key)
        if (
            strictString(endpoint, METHOD_FIELD) != expectedMethod ||
            strictString(endpoint, PATH_FIELD) != expectedPath
        ) {
            throw ProbeFailure(ConnectionFailureReason.INCOMPATIBLE_API)
        }
    }

    private fun strictObject(json: JSONObject, key: String): JSONObject =
        json.opt(key) as? JSONObject
            ?: throw ProbeFailure(ConnectionFailureReason.INCOMPATIBLE_API)

    private fun strictString(json: JSONObject, key: String): String =
        json.opt(key) as? String
            ?: throw ProbeFailure(ConnectionFailureReason.INCOMPATIBLE_API)

    private fun strictBoolean(json: JSONObject, key: String): Boolean =
        json.opt(key) as? Boolean
            ?: throw ProbeFailure(ConnectionFailureReason.INCOMPATIBLE_API)

    private fun failure(reason: ConnectionFailureReason) =
        HermesConnectionResult.Failure(reason)

    private class ProbeFailure(
        val reason: ConnectionFailureReason
    ) : Exception()

    private companion object {
        const val MAX_RESPONSE_BYTES = 256 * 1024
        const val MAX_API_KEY_CHARACTERS = 4096
        const val BUFFER_SIZE = 8 * 1024
        const val HTTPS_SCHEME = "https"
        const val HEALTH_SEGMENT = "health"
        const val V1_SEGMENT = "v1"
        const val CAPABILITIES_SEGMENT = "capabilities"
        const val AUTHORIZATION_HEADER = "Authorization"
        const val BEARER_PREFIX = "Bearer "
        const val HEALTH_OK = "ok"
        const val HERMES_PLATFORM = "hermes-agent"
        const val CAPABILITIES_OBJECT = "hermes.api_server.capabilities"
        const val BEARER_TYPE = "bearer"
        const val SERVER_AGENT_MODE = "server_agent"
        const val SERVER_TOOL_EXECUTION = "server"
        const val STATUS_FIELD = "status"
        const val PLATFORM_FIELD = "platform"
        const val VERSION_FIELD = "version"
        const val OBJECT_FIELD = "object"
        const val AUTH_FIELD = "auth"
        const val RUNTIME_FIELD = "runtime"
        const val FEATURES_FIELD = "features"
        const val ENDPOINTS_FIELD = "endpoints"
        const val TYPE_FIELD = "type"
        const val REQUIRED_FIELD = "required"
        const val MODE_FIELD = "mode"
        const val TOOL_EXECUTION_FIELD = "tool_execution"
        const val SESSION_RESOURCES_FIELD = "session_resources"
        const val SESSION_CHAT_STREAMING_FIELD = "session_chat_streaming"
        const val METHOD_FIELD = "method"
        const val PATH_FIELD = "path"
        const val HEALTH_ENDPOINT = "health"
        const val SESSIONS_ENDPOINT = "sessions"
        const val SESSION_CHAT_STREAM_ENDPOINT = "session_chat_stream"
        const val GET_METHOD = "GET"
        const val POST_METHOD = "POST"
        const val HEALTH_PATH = "/health"
        const val SESSIONS_PATH = "/api/sessions"
        const val SESSION_CHAT_STREAM_PATH = "/api/sessions/{session_id}/chat/stream"

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()
    }
}
