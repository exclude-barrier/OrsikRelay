package io.github.excludebarrier.orsikrelay.network

import io.github.excludebarrier.orsikrelay.domain.profile.ProfileBaseUrlValidator
import io.github.excludebarrier.orsikrelay.domain.profile.SecretValue
import io.github.excludebarrier.orsikrelay.domain.run.RunSteerError
import io.github.excludebarrier.orsikrelay.domain.run.RunSteerResult
import io.github.excludebarrier.orsikrelay.domain.run.RunSteerTransport
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Performs the single authenticated
 * `POST /v1/runs/{run_id}/steer` write. This class is
 * the transport only; profile lookup and credential access happen in
 * the repository layer, so the key never reaches this class except as
 * the scoped [SecretValue] argument.
 *
 * Wire contract (verified read-only against the installed server):
 * - the request carries the canonical JSON body
 *   `{"input":"<text>"}` with `Content-Type: application/json`;
 * - the client sends the Bearer key only; no `X-Hermes-Session-*`
 *   headers are sent;
 * - the accepted answer is
 *   `{"object":"hermes.run.steer","run_id":<exactly requested>,"accepted":true}`;
 *   it means queued/accepted only, the run stays active;
 * - 409 means `run_not_accepting_steer` or `steer_not_accepted` and is
 *   returned as the closed [RunSteerResult.NotAccepting] outcome; it is
 *   never resubmitted automatically.
 *
 * Local text normalization (defined, not server-derived):
 * - leading and trailing whitespace (including line breaks) is trimmed
 *   before the wire, mirroring the server's own trim;
 * - inner whitespace, including `\n`, `\r\n` and `\t`, is preserved:
 *   multi-line normal text is a valid steer;
 * - a text that is blank after trimming is rejected locally;
 * - ISO control characters other than `\n`, `\r` and `\t` (NUL among
 *   them) are rejected locally, like the established chat message
 *   rule, but ordinary line breaks are never forbidden;
 * - [MAX_STEER_TEXT_LENGTH] mirrors the established project limit for
 *   user text ([io.github.excludebarrier.orsikrelay.domain.chat.ChatMessageValidator
 *   .MAX_MESSAGE_LENGTH]); no server limit is assumed.
 *
 * Security:
 * - HTTPS base URLs only (validated by [ProfileBaseUrlValidator]); an
 *   existing path prefix of the base URL is preserved;
 * - the run ID is added as exactly one encoded path segment via
 *   `addPathSegment`;
 * - the bearer key is only ever materialized inside
 *   [SecretValue.useRaw] and placed in the `Authorization` header;
 * - redirects and connection retries are disabled; the steer POST is a
 *   non-idempotent write and is never repeated automatically;
 * - only HTTP 200 with a JSON media type is accepted; the body is
 *   bounded and parsed strictly;
 * - every failure maps to a closed [RunSteerError]; no response body,
 *   exception text, URL, key, steer text or server detail ever appears
 *   in a result or log.
 */
class HermesRunSteerClient(
    okHttpClient: OkHttpClient = HermesHttp.defaultClient()
) : RunSteerTransport {

    private val client = okHttpClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()

    override fun steerRun(
        baseUrl: String,
        apiKey: SecretValue,
        runId: String,
        text: String
    ): RunSteerResult {
        val base = ProfileBaseUrlValidator.validate(baseUrl).getOrNull()
            ?: return RunSteerResult.Failure(RunSteerError.InvalidInput)
        if (!isSafeOpaqueId(runId)) {
            return RunSteerResult.Failure(RunSteerError.InvalidInput)
        }
        val normalizedText = normalizeText(text)
            ?: return RunSteerResult.Failure(RunSteerError.InvalidInput)
        if (!isSafeApiKey(apiKey)) {
            return RunSteerResult.Failure(RunSteerError.InvalidInput)
        }

        // Exactly one encoded segment per path piece: the run ID is
        // never concatenated or added via `addPathSegments`.
        val url = base.toHttpUrl().newBuilder()
            .addPathSegment(V1_SEGMENT)
            .addPathSegment(RUNS_SEGMENT)
            .addPathSegment(runId)
            .addPathSegment(STEER_SEGMENT)
            .build()
        val bodyJson = try {
            JSONObject().put(INPUT_FIELD, normalizedText).toString()
        } catch (_: Exception) {
            return RunSteerResult.Failure(RunSteerError.InvalidInput)
        }
        val request = apiKey.useRaw { rawKey ->
            Request.Builder()
                .url(url)
                .post(bodyJson.toByteArray().toRequestBody(JSON_MEDIA_TYPE))
                .header(AUTHORIZATION_HEADER, "$BEARER_PREFIX$rawKey")
                .header(CONTENT_TYPE_HEADER, CONTENT_TYPE_HEADER_VALUE)
                .build()
        }

        return try {
            client.newCall(request).execute().use { response ->
                when (response.code) {
                    OK_CODE -> {
                        requireJsonContentType(response) {
                            throw SteerFailure(RunSteerError.IncompatibleApi)
                        }
                        parseQueuedAnswer(
                            readBoundedUtf8(response.body, MAX_RESPONSE_BYTES) {
                                throw SteerFailure(RunSteerError.IncompatibleApi)
                            },
                            runId
                        )
                    }
                    401, 403 -> RunSteerResult.Failure(RunSteerError.InvalidCredentials)
                    NOT_FOUND_CODE -> RunSteerResult.Failure(RunSteerError.NotFound)
                    CONFLICT_CODE -> {
                        // Only the documented conflict codes mean "not
                        // accepting"; any other 409 fails closed instead of
                        // being misreported as a benign outcome.
                        val code = extractErrorCode(
                            readBoundedUtf8(response.body, MAX_RESPONSE_BYTES) {
                                throw SteerFailure(RunSteerError.IncompatibleApi)
                            }
                        )
                        if (code != null && code in NOT_ACCEPTING_CODES) {
                            RunSteerResult.NotAccepting(runId)
                        } else {
                            RunSteerResult.Failure(RunSteerError.Http)
                        }
                    }
                    RATE_LIMITED_CODE -> RunSteerResult.Failure(RunSteerError.RateLimited)
                    else -> RunSteerResult.Failure(RunSteerError.Http)
                }
            }
        } catch (error: SteerFailure) {
            RunSteerResult.Failure(error.reason)
        } catch (_: SocketTimeoutException) {
            RunSteerResult.Failure(RunSteerError.Timeout)
        } catch (_: SSLException) {
            RunSteerResult.Failure(RunSteerError.Tls)
        } catch (_: InterruptedIOException) {
            RunSteerResult.Failure(RunSteerError.Timeout)
        } catch (error: IOException) {
            RunSteerResult.Failure(if (error.hasTlsFailure()) RunSteerError.Tls else RunSteerError.Network)
        } catch (_: Exception) {
            RunSteerResult.Failure(RunSteerError.IncompatibleApi)
        }
    }

    /**
     * Strict contract check of the steer answer: JSON object root,
     * string `object` equal to exactly [STEER_OBJECT], string
     * `run_id` equal to exactly the requested ID and boolean
     * `accepted` equal to `true`. Extra server fields are ignored. Any
     * other shape is a closed protocol violation.
     */
    private fun parseQueuedAnswer(text: String, requestedRunId: String): RunSteerResult {
        val root = try {
            JSONObject(text)
        } catch (_: Exception) {
            throw SteerFailure(RunSteerError.IncompatibleApi)
        }
        if (root.opt(OBJECT_FIELD) != STEER_OBJECT) {
            throw SteerFailure(RunSteerError.IncompatibleApi)
        }
        val runId = root.opt(RUN_ID_FIELD) as? String
            ?: throw SteerFailure(RunSteerError.IncompatibleApi)
        if (runId != requestedRunId) {
            throw SteerFailure(RunSteerError.IncompatibleApi)
        }
        if (root.opt(ACCEPTED_FIELD) != true) {
            throw SteerFailure(RunSteerError.IncompatibleApi)
        }
        return RunSteerResult.Queued(runId)
    }

    private fun normalizeText(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.length > MAX_STEER_TEXT_LENGTH) return null
        for (character in trimmed) {
            if (character.isISOControl() &&
                character != '\n' &&
                character != '\r' &&
                character != '\t'
            ) {
                return null
            }
        }
        return trimmed
    }

    private class SteerFailure(val reason: RunSteerError) : Exception()

    /**
     * Builds the production default steer client. Tests may keep their
     * own short timeouts; this factory is the single configurable
     * production default.
     */
    internal companion object {
        const val MAX_RESPONSE_BYTES = 512 * 1024
        const val V1_SEGMENT = "v1"
        const val RUNS_SEGMENT = "runs"
        const val STEER_SEGMENT = "steer"
        const val AUTHORIZATION_HEADER = "Authorization"
        const val BEARER_PREFIX = "Bearer "
        const val CONTENT_TYPE_HEADER = "Content-Type"
        const val CONTENT_TYPE_HEADER_VALUE = "application/json"
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
        const val OBJECT_FIELD = "object"
        const val RUN_ID_FIELD = "run_id"
        const val ACCEPTED_FIELD = "accepted"
        const val INPUT_FIELD = "input"
        const val STEER_OBJECT = "hermes.run.steer"
        const val MAX_STEER_TEXT_LENGTH = 32_768
        const val OK_CODE = 200
        const val NOT_FOUND_CODE = 404
        const val CONFLICT_CODE = 409
        // Documented 409 `error.code` values that mean "run not accepting steer".
        val NOT_ACCEPTING_CODES = setOf("run_not_accepting_steer", "steer_not_accepted")
        const val RATE_LIMITED_CODE = 429
    }
}
