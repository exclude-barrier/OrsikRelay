package io.github.excludebarrier.orsikrelay.network

import io.github.excludebarrier.orsikrelay.domain.connection.ConnectionFailureReason
import io.github.excludebarrier.orsikrelay.domain.connection.ConnectionProbe
import io.github.excludebarrier.orsikrelay.domain.connection.HermesConnectionResult
import io.github.excludebarrier.orsikrelay.domain.profile.ProfileDraft
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject

class HermesConnectionClient(
    okHttpClient: OkHttpClient = HermesHttp.defaultClient()
) : ConnectionProbe {
    private val client = okHttpClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()

    override fun probe(draft: ProfileDraft): HermesConnectionResult {
        if (!isSafeApiKey(draft.apiKey)) {
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
            val negotiated = parseCapabilities(capabilities)
            HermesConnectionResult.Success(
                version = version,
                sessionsSupported = true,
                sessionStreamingSupported = true,
                sessionChatInteractiveApprovals = negotiated.sessionChatInteractiveApprovals,
                deviceCredentialsSupported = negotiated.deviceCredentials
            )
        } catch (error: ProbeFailure) {
            failure(error.reason)
        } catch (_: SocketTimeoutException) {
            failure(ConnectionFailureReason.TIMEOUT)
        } catch (_: SSLException) {
            failure(ConnectionFailureReason.TLS)
        } catch (_: InterruptedIOException) {
            failure(ConnectionFailureReason.TIMEOUT)
        } catch (error: IOException) {
            failure(
                if (error.hasTlsFailure()) ConnectionFailureReason.TLS else ConnectionFailureReason.NETWORK
            )
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
            val text = readBoundedUtf8(response.body, MAX_RESPONSE_BYTES) {
                throw ProbeFailure(ConnectionFailureReason.INCOMPATIBLE_API)
            }
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

    /**
     * Validates the hard capabilities contract and then negotiates the two
     * soft, mobile-specific capabilities (session-chat interactive approvals
     * and device credentials). Both are versioned by the single explicit
     * mobile-protocol contract: this client speaks [MOBILE_PROTOCOL_VERSION]
     * and accepts only [SUPPORTED_MOBILE_PROTOCOL_VERSIONS]. Each capability
     * remains fail-closed: an absent, partial, malformed, legacy-only or
     * unknown-version advertisement returns `false` without breaking the
     * otherwise compatible connection.
     */
    private fun parseCapabilities(json: JSONObject): NegotiatedCapabilities {
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
        return NegotiatedCapabilities(
            sessionChatInteractiveApprovals =
                supportsSessionChatInteractiveApprovals(features, endpoints),
            deviceCredentials = supportsDeviceCredentials(features, endpoints)
        )
    }

    private fun supportsDeviceCredentials(
        features: JSONObject,
        endpoints: JSONObject
    ): Boolean {
        val contract = features.opt(DEVICE_CREDENTIALS_FIELD) as? JSONObject
            ?: return false
        if (objectKeys(contract) != DEVICE_CREDENTIAL_CONTRACT_FIELDS) return false
        if (
            contract.opt(ENABLED_FIELD) != true ||
            !isSupportedMobileProtocolVersion(contract.opt(PROTOCOL_VERSION_FIELD)) ||
            contract.opt(PAIR_ENDPOINT_FIELD) != DEVICE_PAIR_ENDPOINT ||
            contract.opt(SELF_REVOKE_ENDPOINT_FIELD) != DEVICE_SELF_REVOKE_ENDPOINT
        ) return false
        val pairEndpoint = endpoints.opt(DEVICE_PAIR_ENDPOINT) as? JSONObject ?: return false
        val revokeEndpoint = endpoints.opt(DEVICE_SELF_REVOKE_ENDPOINT) as? JSONObject ?: return false
        return objectKeys(pairEndpoint) == APPROVAL_ENDPOINT_FIELDS &&
            pairEndpoint.opt(METHOD_FIELD) == POST_METHOD &&
            pairEndpoint.opt(PATH_FIELD) == DEVICE_PAIR_PATH &&
            objectKeys(revokeEndpoint) == APPROVAL_ENDPOINT_FIELDS &&
            revokeEndpoint.opt(METHOD_FIELD) == POST_METHOD &&
            revokeEndpoint.opt(PATH_FIELD) == DEVICE_SELF_REVOKE_PATH
    }

    private fun supportsSessionChatInteractiveApprovals(
        features: JSONObject,
        endpoints: JSONObject
    ): Boolean {
        if (features.opt(RUN_APPROVAL_RESPONSE_FIELD) != true) return false
        val contract = features.opt(SESSION_CHAT_APPROVALS_FIELD) as? JSONObject
            ?: return false
        if (objectKeys(contract) != APPROVAL_CONTRACT_FIELDS) return false
        val choices = contract.opt(CHOICES_FIELD) as? org.json.JSONArray
            ?: return false
        if (
            contract.opt(ENABLED_FIELD) != true ||
            !isSupportedMobileProtocolVersion(contract.opt(PROTOCOL_VERSION_FIELD)) ||
            contract.opt(EVENT_FIELD) != APPROVAL_EVENT ||
            contract.opt(RESPONSE_ENDPOINT_FIELD) != RUN_APPROVAL_ENDPOINT ||
            choices.length() != 2 ||
            choices.opt(0) != APPROVAL_ONCE ||
            choices.opt(1) != APPROVAL_DENY
        ) {
            return false
        }
        val endpoint = endpoints.opt(RUN_APPROVAL_ENDPOINT) as? JSONObject
            ?: return false
        return objectKeys(endpoint) == APPROVAL_ENDPOINT_FIELDS &&
            endpoint.opt(METHOD_FIELD) == POST_METHOD &&
            endpoint.opt(PATH_FIELD) == RUN_APPROVAL_PATH
    }

    private fun objectKeys(json: JSONObject): Set<String> {
        val keys = mutableSetOf<String>()
        val iterator = json.keys()
        while (iterator.hasNext()) keys += iterator.next()
        return keys
    }

    /**
     * The explicit, value-strict mobile-protocol version gate. Returns
     * `true` only when [value] is an integer member of
     * [SUPPORTED_MOBILE_PROTOCOL_VERSIONS]. A missing version (`null`), a
     * non-integer (string, float, array) or an out-of-range integer all
     * fail closed, so no unknown or legacy version can ever enable a
     * mobile capability implicitly.
     */
    private fun isSupportedMobileProtocolVersion(value: Any?): Boolean =
        value is Int && value in SUPPORTED_MOBILE_PROTOCOL_VERSIONS

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

    private data class NegotiatedCapabilities(
        val sessionChatInteractiveApprovals: Boolean,
        val deviceCredentials: Boolean
    )

    private class ProbeFailure(
        val reason: ConnectionFailureReason
    ) : Exception()

    private companion object {
        const val MAX_RESPONSE_BYTES = 256 * 1024
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
        const val RUN_APPROVAL_RESPONSE_FIELD = "run_approval_response"
        const val SESSION_CHAT_APPROVALS_FIELD = "session_chat_interactive_approvals"
        const val DEVICE_CREDENTIALS_FIELD = "device_credentials"
        const val PAIR_ENDPOINT_FIELD = "pair_endpoint"
        const val SELF_REVOKE_ENDPOINT_FIELD = "self_revoke_endpoint"
        const val ENABLED_FIELD = "enabled"
        const val PROTOCOL_VERSION_FIELD = "protocol_version"
        const val EVENT_FIELD = "event"
        const val CHOICES_FIELD = "choices"
        const val RESPONSE_ENDPOINT_FIELD = "response_endpoint"
        /**
         * The single, explicit mobile-protocol version this client speaks.
         * Every soft mobile capability (session-chat approvals, device
         * credentials) is versioned by this one contract. [SUPPORTED_MOBILE_PROTOCOL_VERSIONS]
         * is the closed set the client will negotiate; a contract advertising
         * any other `protocol_version` fails closed. To support a future
         * version, extend the set and add its field schema - never widen the
         * v1 schema implicitly.
         */
        const val MOBILE_PROTOCOL_VERSION = 1
        val SUPPORTED_MOBILE_PROTOCOL_VERSIONS = setOf(MOBILE_PROTOCOL_VERSION)
        const val APPROVAL_EVENT = "approval.request"
        const val APPROVAL_ONCE = "once"
        const val APPROVAL_DENY = "deny"
        val APPROVAL_CONTRACT_FIELDS = setOf(
            ENABLED_FIELD,
            PROTOCOL_VERSION_FIELD,
            EVENT_FIELD,
            CHOICES_FIELD,
            RESPONSE_ENDPOINT_FIELD
        )
        val APPROVAL_ENDPOINT_FIELDS = setOf(METHOD_FIELD, PATH_FIELD)
        const val DEVICE_PAIR_ENDPOINT = "device_pair"
        const val DEVICE_SELF_REVOKE_ENDPOINT = "device_self_revoke"
        val DEVICE_CREDENTIAL_CONTRACT_FIELDS = setOf(
            ENABLED_FIELD,
            PROTOCOL_VERSION_FIELD,
            PAIR_ENDPOINT_FIELD,
            SELF_REVOKE_ENDPOINT_FIELD
        )
        const val METHOD_FIELD = "method"
        const val PATH_FIELD = "path"
        const val HEALTH_ENDPOINT = "health"
        const val SESSIONS_ENDPOINT = "sessions"
        const val SESSION_CHAT_STREAM_ENDPOINT = "session_chat_stream"
        const val RUN_APPROVAL_ENDPOINT = "run_approval"
        const val GET_METHOD = "GET"
        const val POST_METHOD = "POST"
        const val HEALTH_PATH = "/health"
        const val SESSIONS_PATH = "/api/sessions"
        const val SESSION_CHAT_STREAM_PATH = "/api/sessions/{session_id}/chat/stream"
        const val RUN_APPROVAL_PATH = "/v1/runs/{run_id}/approval"
        const val DEVICE_PAIR_PATH = "/v1/devices/pair"
        const val DEVICE_SELF_REVOKE_PATH = "/v1/devices/self/revoke"
    }
}
