package io.github.excludebarrier.orsikrelay.network

import io.github.excludebarrier.orsikrelay.domain.device.DeviceCredential
import io.github.excludebarrier.orsikrelay.domain.device.DeviceCredentialKey
import io.github.excludebarrier.orsikrelay.domain.device.DevicePairCode
import io.github.excludebarrier.orsikrelay.domain.device.DevicePairError
import io.github.excludebarrier.orsikrelay.domain.device.DevicePairResult
import io.github.excludebarrier.orsikrelay.domain.device.DevicePairTransport
import io.github.excludebarrier.orsikrelay.domain.profile.ProfileBaseUrlValidator
import io.github.excludebarrier.orsikrelay.domain.profile.SecretValue
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Performs the single UNAUTHENTICATED
 * `POST /v1/devices/pair` write (Mission 017A). This class is the
 * transport only; it takes the base URL and the raw pairing code and
 * never receives any credential.
 *
 * Wire contract (server contract v1, docs/mobile-wire-contract.md §2.3):
 * - the request carries the exact JSON body `{"code": "<code>"}` with
 *   `Content-Type: application/json` and NO `Authorization` header
 *   (the pairing endpoint is unauthenticated);
 * - the code is normalized locally (trim, uppercase, dashes/whitespace/
 *   underscores removed) and must then be exactly 24 characters from
 *   the closed alphabet (A-Z without I/L/O/U plus 1-9); anything else
 *   is rejected locally WITHOUT a request;
 * - success is HTTP 201 with EXACTLY the field set
 *   `{"object":"hermes.device.credential","device_id":<uuid>,
 *   "device_key":"hd_<43 base64url>","profile":<string>,
 *   "label":<string>,"permissions":[...],"issued_at":<number>}`:
 *   missing or extra fields, a wrong `object` discriminator, a
 *   malformed device key (not `hd_` + exactly 46 characters), a
 *   non-UUID device id, permissions that are not exactly
 *   `approvals`/`chat`/`runs` (any order) or a non-numeric
 *   `issued_at` are all closed protocol violations;
 * - 400 `pairing_code_invalid` / `invalid_pair_request`, 409
 *   `device_limit_exceeded`, 429 `pair_rate_limited`, 500
 *   `pair_failed` map to their closed error values; any OTHER
 *   `error.code` on those statuses is fail-closed (never silently
 *   adopted);
 * - 401/403, 404 and every other status map to closed reasons without
 *   reading the body.
 *
 * Security:
 * - HTTPS base URLs only (validated by [ProfileBaseUrlValidator]); an
 *   existing path prefix of the base URL is preserved;
 * - the pairing code is a SINGLE-USE secret: redirects and connection
 *   retries are disabled and the POST is never repeated automatically;
 * - only HTTP 201 with a JSON media type is parsed; the body is
 *   bounded and parsed strictly;
 * - every failure maps to a closed [DevicePairError]; the pairing
 *   code, the device key, response bodies, exception text, URLs or
 *   server details never appear in a result or log.
 */
class HermesDevicePairClient(
    okHttpClient: OkHttpClient = defaultDevicePairHttpClient()
) : DevicePairTransport {

    private val client = okHttpClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()

    override fun pairDevice(baseUrl: String, pairingCode: String): DevicePairResult {
        val base = ProfileBaseUrlValidator.validate(baseUrl).getOrNull()
            ?: return DevicePairResult.Failure(DevicePairError.InvalidInput)
        val code = DevicePairCode.normalize(pairingCode)
            ?: return DevicePairResult.Failure(DevicePairError.InvalidInput)

        val url = base.toHttpUrl().newBuilder()
            .addPathSegment(V1_SEGMENT)
            .addPathSegment(DEVICES_SEGMENT)
            .addPathSegment(PAIR_SEGMENT)
            .build()
        // The exact body contract: exactly one field, `code`. The
        // normalized canonical code is what goes on the wire.
        val bodyJson = try {
            JSONObject().put(CODE_FIELD, code).toString()
        } catch (_: Exception) {
            return DevicePairResult.Failure(DevicePairError.InvalidInput)
        }
        // Unauthenticated: no Authorization header is set.
        val request = Request.Builder()
            .url(url)
            .post(bodyJson.toByteArray().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                when (response.code) {
                    CREATED_CODE -> {
                        requireJsonContentType(response)
                        parsePairedAnswer(readBoundedUtf8(response.body))
                    }
                    BAD_REQUEST_CODE -> mapClosedErrorCode(response, BAD_REQUEST_CODE)
                    CONFLICT_CODE -> mapClosedErrorCode(response, CONFLICT_CODE)
                    RATE_LIMITED_CODE -> mapClosedErrorCode(response, RATE_LIMITED_CODE)
                    INTERNAL_ERROR_CODE -> mapClosedErrorCode(response, INTERNAL_ERROR_CODE)
                    401, 403 -> DevicePairResult.Failure(DevicePairError.InvalidCredentials)
                    NOT_FOUND_CODE -> DevicePairResult.Failure(DevicePairError.NotFound)
                    else -> DevicePairResult.Failure(DevicePairError.Http)
                }
            }
        } catch (error: PairFailure) {
            DevicePairResult.Failure(error.reason)
        } catch (_: SocketTimeoutException) {
            DevicePairResult.Failure(DevicePairError.Timeout)
        } catch (_: SSLException) {
            DevicePairResult.Failure(DevicePairError.Tls)
        } catch (_: InterruptedIOException) {
            DevicePairResult.Failure(DevicePairError.Timeout)
        } catch (_: IOException) {
            DevicePairResult.Failure(DevicePairError.Network)
        } catch (_: Exception) {
            DevicePairResult.Failure(DevicePairError.IncompatibleApi)
        }
    }

    /**
     * Closed error-code mapping for 400/409/429/500. The server error
     * body is `{"error":{"message","type","code"}}`; only the code is
     * read, and only the one expected for this status is adopted. An
     * unknown, missing or unparsable code is fail-closed
     * [DevicePairError.IncompatibleApi] — never a silent adoption.
     */
    private fun mapClosedErrorCode(response: Response, status: Int): DevicePairResult {
        val code = extractErrorCode(readBoundedUtf8(response.body))
            ?: return DevicePairResult.Failure(DevicePairError.IncompatibleApi)
        val error = when (status) {
            BAD_REQUEST_CODE -> when (code) {
                PAIRING_CODE_INVALID -> DevicePairError.PairingCodeInvalid
                INVALID_PAIR_REQUEST -> DevicePairError.InvalidPairRequest
                else -> DevicePairError.IncompatibleApi
            }
            CONFLICT_CODE -> when (code) {
                DEVICE_LIMIT_EXCEEDED -> DevicePairError.DeviceLimitExceeded
                else -> DevicePairError.IncompatibleApi
            }
            RATE_LIMITED_CODE -> when (code) {
                PAIR_RATE_LIMITED -> DevicePairError.PairRateLimited
                else -> DevicePairError.IncompatibleApi
            }
            INTERNAL_ERROR_CODE -> when (code) {
                PAIR_FAILED -> DevicePairError.PairFailed
                else -> DevicePairError.IncompatibleApi
            }
            else -> DevicePairError.Http
        }
        return DevicePairResult.Failure(error)
    }

    private fun extractErrorCode(text: String): String? {
        val root = try {
            JSONObject(text)
        } catch (_: Exception) {
            return null
        }
        val error = root.opt(ERROR_FIELD) as? JSONObject ?: return null
        return error.opt(ERROR_CODE_FIELD) as? String
    }

    /** Only HTTP 201 continues; the media type must be JSON. */
    private fun requireJsonContentType(response: Response) {
        val type = response.header(CONTENT_TYPE_HEADER)
            ?.substringBefore(';')
            ?.trim()
        if (!JSON_MEDIA_TYPE_VALUE.equals(type, ignoreCase = true)) {
            throw PairFailure(DevicePairError.IncompatibleApi)
        }
    }

    /**
     * Strict contract check of the 201 answer: JSON object root with
     * EXACTLY the closed field set (missing or extra fields fail
     * closed), `object` exactly `hermes.device.credential`, UUID
     * `device_id`, `device_key` exactly `hd_` + 43 characters (46
     * total), string `profile`/`label`, `permissions` exactly the set
     * `approvals`/`chat`/`runs` in any order, and a finite numeric
     * `issued_at`. No other server field is read or propagated.
     */
    private fun parsePairedAnswer(text: String): DevicePairResult {
        val root = try {
            JSONObject(text)
        } catch (_: Exception) {
            throw PairFailure(DevicePairError.IncompatibleApi)
        }
        val fields = HashSet<String>()
        root.keys().forEach { fields.add(it) }
        if (fields != CREDENTIAL_FIELDS) {
            throw PairFailure(DevicePairError.IncompatibleApi)
        }
        if (root.opt(OBJECT_FIELD) as? String != CREDENTIAL_OBJECT) {
            throw PairFailure(DevicePairError.IncompatibleApi)
        }
        val deviceId = root.opt(DEVICE_ID_FIELD) as? String
            ?: throw PairFailure(DevicePairError.IncompatibleApi)
        if (isUuid(deviceId) != true) {
            throw PairFailure(DevicePairError.IncompatibleApi)
        }
        val deviceKey = root.opt(DEVICE_KEY_FIELD) as? String
            ?: throw PairFailure(DevicePairError.IncompatibleApi)
        if (!DeviceCredentialKey.isValid(deviceKey)) {
            throw PairFailure(DevicePairError.IncompatibleApi)
        }
        val profile = root.opt(PROFILE_FIELD) as? String
            ?: throw PairFailure(DevicePairError.IncompatibleApi)
        val label = root.opt(LABEL_FIELD) as? String
            ?: throw PairFailure(DevicePairError.IncompatibleApi)
        if (!isSafeServerString(profile, MAX_SAFE_SERVER_STRING_LENGTH) ||
            !isSafeServerString(label, MAX_SAFE_SERVER_STRING_LENGTH)
        ) {
            throw PairFailure(DevicePairError.IncompatibleApi)
        }
        val permissionsArray = root.opt(PERMISSIONS_FIELD) as? JSONArray
            ?: throw PairFailure(DevicePairError.IncompatibleApi)
        val permissions = ArrayList<String>(permissionsArray.length())
        for (index in 0 until permissionsArray.length()) {
            val permission = permissionsArray.opt(index) as? String
                ?: throw PairFailure(DevicePairError.IncompatibleApi)
            permissions.add(permission)
        }
        if (permissions.size != EXPECTED_PERMISSIONS.size ||
            permissions.toSet() != EXPECTED_PERMISSIONS
        ) {
            throw PairFailure(DevicePairError.IncompatibleApi)
        }
        val issuedAt = root.opt(ISSUED_AT_FIELD)
        val issuedAtValue = (issuedAt as? Number)?.toDouble()
        if (issuedAtValue == null || !issuedAtValue.isFinite() || issuedAtValue < 0.0) {
            throw PairFailure(DevicePairError.IncompatibleApi)
        }
        return DevicePairResult.Paired(
            DeviceCredential(
                deviceId = deviceId,
                deviceKey = SecretValue(deviceKey),
                profile = profile,
                label = label,
                permissions = permissions,
                issuedAt = issuedAtValue
            )
        )
    }

    private fun readBoundedUtf8(body: ResponseBody?): String {
        if (body == null || body.contentLength() > MAX_RESPONSE_BYTES) {
            throw PairFailure(DevicePairError.IncompatibleApi)
        }
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_SIZE)
        body.byteStream().use { input ->
            var total = 0
            while (true) {
                val remaining = MAX_RESPONSE_BYTES + 1 - total
                if (remaining <= 0) {
                    throw PairFailure(DevicePairError.IncompatibleApi)
                }
                val read = input.read(buffer, 0, minOf(buffer.size, remaining))
                if (read == -1) break
                output.write(buffer, 0, read)
                total += read
                if (total > MAX_RESPONSE_BYTES) {
                    throw PairFailure(DevicePairError.IncompatibleApi)
                }
            }
        }
        val bytes = output.toByteArray()
        val text = try {
            decodeUtf8Strict(bytes)
        } finally {
            bytes.fill(0)
            buffer.fill(0)
        }
        return text ?: throw PairFailure(DevicePairError.IncompatibleApi)
    }

    private fun isUuid(value: String): Boolean = isStrictUuid(value)

    private class PairFailure(val reason: DevicePairError) : Exception()

    /**
     * Builds the production default device-pair client. Tests may keep
     * their own short timeouts; this factory is the single configurable
     * production default.
     */
    internal companion object {
        const val MAX_RESPONSE_BYTES = 512 * 1024
        const val BUFFER_SIZE = 8 * 1024
        const val V1_SEGMENT = "v1"
        const val DEVICES_SEGMENT = "devices"
        const val PAIR_SEGMENT = "pair"
        const val CONTENT_TYPE_HEADER = "Content-Type"
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
        const val JSON_MEDIA_TYPE_VALUE = "application/json"
        const val CODE_FIELD = "code"
        const val OBJECT_FIELD = "object"
        const val DEVICE_ID_FIELD = "device_id"
        const val DEVICE_KEY_FIELD = "device_key"
        const val PROFILE_FIELD = "profile"
        const val LABEL_FIELD = "label"
        const val PERMISSIONS_FIELD = "permissions"
        const val ISSUED_AT_FIELD = "issued_at"
        const val CREDENTIAL_OBJECT = "hermes.device.credential"
        val CREDENTIAL_FIELDS = setOf(
            OBJECT_FIELD,
            DEVICE_ID_FIELD,
            DEVICE_KEY_FIELD,
            PROFILE_FIELD,
            LABEL_FIELD,
            PERMISSIONS_FIELD,
            ISSUED_AT_FIELD
        )
        val EXPECTED_PERMISSIONS = setOf("approvals", "chat", "runs")
        const val ERROR_FIELD = "error"
        const val ERROR_CODE_FIELD = "code"
        const val PAIRING_CODE_INVALID = "pairing_code_invalid"
        const val INVALID_PAIR_REQUEST = "invalid_pair_request"
        const val DEVICE_LIMIT_EXCEEDED = "device_limit_exceeded"
        const val PAIR_RATE_LIMITED = "pair_rate_limited"
        const val PAIR_FAILED = "pair_failed"
        const val CREATED_CODE = 201
        const val BAD_REQUEST_CODE = 400
        const val NOT_FOUND_CODE = 404
        const val CONFLICT_CODE = 409
        const val RATE_LIMITED_CODE = 429
        const val INTERNAL_ERROR_CODE = 500

        fun defaultDevicePairHttpClient(): OkHttpClient = OkHttpClient.Builder()
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
