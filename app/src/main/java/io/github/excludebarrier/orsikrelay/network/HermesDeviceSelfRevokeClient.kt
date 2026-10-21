package io.github.excludebarrier.orsikrelay.network

import io.github.excludebarrier.orsikrelay.domain.device.DeviceCredentialKey
import io.github.excludebarrier.orsikrelay.domain.device.DeviceSelfRevokeError
import io.github.excludebarrier.orsikrelay.domain.device.DeviceSelfRevokeResult
import io.github.excludebarrier.orsikrelay.domain.device.DeviceSelfRevokeTransport
import io.github.excludebarrier.orsikrelay.domain.profile.ProfileBaseUrlValidator
import io.github.excludebarrier.orsikrelay.domain.profile.SecretValue
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Performs the single authenticated
 * `POST /v1/devices/self/revoke` write. This class is
 * the transport only; profile lookup and credential access happen in
 * the repository layer, so the key never reaches this class except as
 * the scoped [SecretValue] argument.
 *
 * Wire contract (server contract v1, docs/mobile-wire-contract.md §2.4):
 * - the request carries `Authorization: Bearer <device key>` and an
 *   exactly zero-byte request body with no `Content-Type` header (OkHttp
 *   requires a RequestBody object for POST, but the server contract is
 *   bodyless);
 * - success is HTTP 200 with EXACTLY the field set
 *   `{"revoked":true|false,"device_id":<uuid>}`: `revoked=true` is the
 *   fresh revocation, `revoked=false` the already-revoked answer; the
 *   revocation takes effect immediately (no cache);
 * - 401 (`gateway_auth_failed`) is the closed invalid-credentials
 *   reason: the 401 answer does NOT distinguish the credential class
 *   (API key, unknown device key, revoked device key) and is the same
 *   answer for all of them — the client must not treat it as an
 *   oracle;
 * - 403/404/429 and every other status map to closed reasons without
 *   reading the body.
 *
 * Security:
 * - HTTPS base URLs only (validated by [ProfileBaseUrlValidator]); an
 *   existing path prefix of the base URL is preserved;
 * - the bearer key is only ever materialized inside
 *   [SecretValue.useRaw] and placed in the `Authorization` header;
 * - redirects and connection retries are disabled; the revocation POST
 *   is never repeated automatically;
 * - only HTTP 200 with a JSON media type is accepted; the body is
 *   bounded and parsed strictly (missing or extra fields fail closed);
 * - every failure maps to a closed [DeviceSelfRevokeError]; no
 *   response body, exception text, URL, key or server detail ever
 *   appears in a result or log.
 */
class HermesDeviceSelfRevokeClient(
    okHttpClient: OkHttpClient = HermesHttp.defaultClient()
) : DeviceSelfRevokeTransport {

    private val client = okHttpClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()

    override fun revokeSelf(
        baseUrl: String,
        deviceKey: SecretValue
    ): DeviceSelfRevokeResult {
        val base = ProfileBaseUrlValidator.validate(baseUrl).getOrNull()
            ?: return DeviceSelfRevokeResult.Failure(DeviceSelfRevokeError.InvalidInput)
        if (!DeviceCredentialKey.isValid(deviceKey)) {
            return DeviceSelfRevokeResult.Failure(DeviceSelfRevokeError.InvalidInput)
        }

        val url = base.toHttpUrl().newBuilder()
            .addPathSegment(V1_SEGMENT)
            .addPathSegment(DEVICES_SEGMENT)
            .addPathSegment(SELF_SEGMENT)
            .addPathSegment(REVOKE_SEGMENT)
            .build()
        // OkHttp requires a RequestBody for POST. Use a zero-byte body with
        // no media type so the wire request remains contractually bodyless.
        val body = ByteArray(0).toRequestBody()
        val request = deviceKey.useRaw { rawKey ->
            Request.Builder()
                .url(url)
                .post(body)
                .header(AUTHORIZATION_HEADER, "$BEARER_PREFIX$rawKey")
                .build()
        }

        return try {
            client.newCall(request).execute().use { response ->
                when (response.code) {
                    OK_CODE -> {
                        requireJsonContentType(response) {
                            throw RevokeFailure(DeviceSelfRevokeError.IncompatibleApi)
                        }
                        parseRevokedAnswer(
                            readBoundedUtf8(response.body, MAX_RESPONSE_BYTES) {
                                throw RevokeFailure(DeviceSelfRevokeError.IncompatibleApi)
                            }
                        )
                    }
                    UNAUTHORIZED_CODE -> {
                        DeviceSelfRevokeResult.Failure(DeviceSelfRevokeError.InvalidCredentials)
                    }
                    FORBIDDEN_CODE -> {
                        DeviceSelfRevokeResult.Failure(DeviceSelfRevokeError.Forbidden)
                    }
                    NOT_FOUND_CODE -> {
                        DeviceSelfRevokeResult.Failure(DeviceSelfRevokeError.NotFound)
                    }
                    RATE_LIMITED_CODE -> {
                        DeviceSelfRevokeResult.Failure(DeviceSelfRevokeError.RateLimited)
                    }
                    else -> DeviceSelfRevokeResult.Failure(DeviceSelfRevokeError.Http)
                }
            }
        } catch (error: RevokeFailure) {
            DeviceSelfRevokeResult.Failure(error.reason)
        } catch (_: SocketTimeoutException) {
            DeviceSelfRevokeResult.Failure(DeviceSelfRevokeError.Timeout)
        } catch (_: SSLException) {
            DeviceSelfRevokeResult.Failure(DeviceSelfRevokeError.Tls)
        } catch (_: InterruptedIOException) {
            DeviceSelfRevokeResult.Failure(DeviceSelfRevokeError.Timeout)
        } catch (error: IOException) {
            DeviceSelfRevokeResult.Failure(if (error.hasTlsFailure()) DeviceSelfRevokeError.Tls else DeviceSelfRevokeError.Network)
        } catch (_: Exception) {
            DeviceSelfRevokeResult.Failure(DeviceSelfRevokeError.IncompatibleApi)
        }
    }

    /**
     * Strict contract check of the 200 answer: JSON object root with
     * EXACTLY the closed field set (missing or extra fields fail
     * closed), boolean `revoked` and a UUID `device_id`. No other
     * server field is read or propagated.
     */
    private fun parseRevokedAnswer(text: String): DeviceSelfRevokeResult {
        val root = try {
            JSONObject(text)
        } catch (_: Exception) {
            throw RevokeFailure(DeviceSelfRevokeError.IncompatibleApi)
        }
        val fields = HashSet<String>()
        root.keys().forEach { fields.add(it) }
        if (fields != REVOKED_FIELDS) {
            throw RevokeFailure(DeviceSelfRevokeError.IncompatibleApi)
        }
        val revoked = root.opt(REVOKED_FIELD) as? Boolean
            ?: throw RevokeFailure(DeviceSelfRevokeError.IncompatibleApi)
        val deviceId = root.opt(DEVICE_ID_FIELD) as? String
            ?: throw RevokeFailure(DeviceSelfRevokeError.IncompatibleApi)
        if (!isStrictUuid(deviceId)) {
            throw RevokeFailure(DeviceSelfRevokeError.IncompatibleApi)
        }
        return DeviceSelfRevokeResult.Revoked(revoked = revoked, deviceId = deviceId)
    }

    private class RevokeFailure(val reason: DeviceSelfRevokeError) : Exception()

    /**
     * Builds the production default self-revoke client. Tests may keep
     * their own short timeouts; this factory is the single configurable
     * production default.
     */
    internal companion object {
        const val MAX_RESPONSE_BYTES = 512 * 1024
        const val V1_SEGMENT = "v1"
        const val DEVICES_SEGMENT = "devices"
        const val SELF_SEGMENT = "self"
        const val REVOKE_SEGMENT = "revoke"
        const val AUTHORIZATION_HEADER = "Authorization"
        const val BEARER_PREFIX = "Bearer "
        const val REVOKED_FIELD = "revoked"
        const val DEVICE_ID_FIELD = "device_id"
        val REVOKED_FIELDS = setOf(REVOKED_FIELD, DEVICE_ID_FIELD)
        const val OK_CODE = 200
        const val UNAUTHORIZED_CODE = 401
        const val FORBIDDEN_CODE = 403
        const val NOT_FOUND_CODE = 404
        const val RATE_LIMITED_CODE = 429
    }
}
