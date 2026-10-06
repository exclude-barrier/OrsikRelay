package io.github.excludebarrier.orsikrelay.network

import io.github.excludebarrier.orsikrelay.domain.profile.ProfileBaseUrlValidator
import io.github.excludebarrier.orsikrelay.domain.profile.SecretValue
import io.github.excludebarrier.orsikrelay.domain.run.RunApprovalChoice
import io.github.excludebarrier.orsikrelay.domain.run.RunApprovalError
import io.github.excludebarrier.orsikrelay.domain.run.RunApprovalResult
import io.github.excludebarrier.orsikrelay.domain.run.RunApprovalTransport
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import org.json.JSONObject

/**
 * Performs the single authenticated
 * `POST /v1/runs/{run_id}/approval` write (Mission 014). This class is
 * the transport only; profile lookup and credential access happen in
 * the repository layer, so the key never reaches this class except as
 * the scoped [SecretValue] argument.
 *
 * Wire contract (verified read-only against the installed server code,
 * `gateway/platforms/api_server_runs.py` `_handle_run_approval`):
 * - the handler reads the JSON body and resolves exactly the pending
 *   approval identified by `request_id`; the request carries
 *   `{"choice": <once|deny>, "request_id": <id>}` with
 *   `Content-Type: application/json`;
 * - the accepted answer is
 *   `{"object":"hermes.run.approval_response","run_id":<exactly
 *   requested>,"choice":<sent choice>,"resolved":<n>=1>}`; it means the
 *   pending approval was resolved with the sent choice;
 * - 409 (`approval_not_pending` / `approval_not_active`) means the run
 *   no longer has a pending approval and is returned as the closed
 *   [RunApprovalResult.NoPending] outcome; it is never resubmitted
 *   automatically;
 * - ownership and auth failures answer 404 / 401-403, a rate limit 429.
 *
 * Local input rules (defined, not server-derived):
 * - [runId] must be non-blank, at most [MAX_RUN_ID_LENGTH] and free of
 *   ISO control characters, mirroring the run-stop client;
 * - [requestId] must be non-blank, at most [MAX_REQUEST_ID_LENGTH] and
 *   free of ISO control characters. The server treats a blank or
 *   over-256-char request ID as an invalid 400; validating locally
 *   keeps the failure closed and avoids a needless write;
 * - only the two least-privilege choices are modelled
 *   ([RunApprovalChoice.ONCE], [RunApprovalChoice.DENY]); a
 *   wider grant is never representable in the client.
 *
 * Security:
 * - HTTPS base URLs only (validated by [ProfileBaseUrlValidator]); an
 *   existing path prefix of the base URL is preserved;
 * - the run ID is added as exactly one encoded path segment via
 *   `addPathSegment`;
 * - the bearer key is only ever materialized inside
 *   [SecretValue.useRaw] and placed in the `Authorization` header;
 * - redirects and connection retries are disabled; the approval POST is
 *   a non-idempotent decision and is never repeated automatically;
 * - only HTTP 200 with a JSON media type is accepted; the body is
 *   bounded and parsed strictly;
 * - every failure maps to a closed [RunApprovalError]; no response
 *   body, exception text, URL, key, run ID or request ID ever appears
 *   in a result or log.
 */
class HermesRunApprovalClient(
    okHttpClient: OkHttpClient = defaultRunApprovalHttpClient()
) : RunApprovalTransport {

    private val client = okHttpClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()

    override fun respondApproval(
        baseUrl: String,
        apiKey: SecretValue,
        runId: String,
        requestId: String,
        choice: RunApprovalChoice
    ): RunApprovalResult {
        val base = ProfileBaseUrlValidator.validate(baseUrl).getOrNull()
            ?: return RunApprovalResult.Failure(RunApprovalError.InvalidInput)
        if (!isSafeRunId(runId) || !isSafeRequestId(requestId)) {
            return RunApprovalResult.Failure(RunApprovalError.InvalidInput)
        }
        val keyIsSafe = apiKey.useRaw { rawKey ->
            rawKey.length in 1..MAX_API_KEY_CHARACTERS &&
                rawKey.none { character ->
                    character.isWhitespace() || character.isISOControl()
                }
        }
        if (!keyIsSafe) {
            return RunApprovalResult.Failure(RunApprovalError.InvalidInput)
        }

        // Exactly one encoded segment per path piece: the run ID is
        // never concatenated or added via `addPathSegments`.
        val url = base.toHttpUrl().newBuilder()
            .addPathSegment(V1_SEGMENT)
            .addPathSegment(RUNS_SEGMENT)
            .addPathSegment(runId)
            .addPathSegment(APPROVAL_SEGMENT)
            .build()
        val bodyJson = try {
            JSONObject()
                .put(CHOICE_FIELD, choice.wireValue)
                .put(REQUEST_ID_FIELD, requestId)
                .toString()
        } catch (_: Exception) {
            return RunApprovalResult.Failure(RunApprovalError.InvalidInput)
        }
        val request = apiKey.useRaw { rawKey ->
            Request.Builder()
                .url(url)
                .post(bodyJson.toByteArray().toRequestBody(JSON_MEDIA_TYPE))
                .header(AUTHORIZATION_HEADER, "$BEARER_PREFIX$rawKey")
                .build()
        }

        return try {
            client.newCall(request).execute().use { response ->
                requireSuccess(response, runId)
                requireJsonContentType(response)
                parseApprovalAnswer(readBoundedUtf8(response.body), runId, choice)
            }
        } catch (_: NoPendingApprovalException) {
            RunApprovalResult.NoPending(runId)
        } catch (error: ApprovalFailure) {
            RunApprovalResult.Failure(error.reason)
        } catch (_: SocketTimeoutException) {
            RunApprovalResult.Failure(RunApprovalError.Timeout)
        } catch (_: SSLException) {
            RunApprovalResult.Failure(RunApprovalError.Tls)
        } catch (_: InterruptedIOException) {
            RunApprovalResult.Failure(RunApprovalError.Timeout)
        } catch (_: IOException) {
            RunApprovalResult.Failure(RunApprovalError.Network)
        } catch (_: Exception) {
            RunApprovalResult.Failure(RunApprovalError.IncompatibleApi)
        }
    }

    /** Only HTTP 200 continues; 409 is the closed NoPending outcome; every other code is a closed reason. */
    private fun requireSuccess(response: Response, runId: String) {
        when (response.code) {
            OK_CODE -> return
            401, 403 -> throw ApprovalFailure(RunApprovalError.InvalidCredentials)
            NOT_FOUND_CODE -> throw ApprovalFailure(RunApprovalError.NotFound)
            CONFLICT_CODE -> {
                // Only the documented conflict codes are "not pending"; any
                // other 409 is fail-closed, never silently reported as resolved.
                val code = extractErrorCode(readBoundedUtf8(response.body))
                if (code != null && code in PENDING_CONFLICT_CODES) {
                    throw NoPendingApprovalException()
                }
                throw ApprovalFailure(RunApprovalError.Http)
            }
            RATE_LIMITED_CODE -> throw ApprovalFailure(RunApprovalError.RateLimited)
            else -> throw ApprovalFailure(RunApprovalError.Http)
        }
    }

    /**
     * Only a JSON media type is accepted (parameters such as a charset
     * are allowed); anything else is a closed protocol violation.
     */
    private fun requireJsonContentType(response: Response) {
        val type = response.header(CONTENT_TYPE_HEADER)
            ?.substringBefore(';')
            ?.trim()
        if (!JSON_MEDIA_TYPE_VALUE.equals(type, ignoreCase = true)) {
            throw ApprovalFailure(RunApprovalError.IncompatibleApi)
        }
    }

    /**
     * Strict contract check of the approval answer: JSON object root,
     * `object` exactly `hermes.run.approval_response`, string
     * `run_id` equal to exactly the requested ID, string `choice`
     * equal to exactly the sent choice, and a positive integer
     * `resolved`. No other server field is read or propagated.
     */
    private fun parseApprovalAnswer(
        text: String,
        requestedRunId: String,
        sentChoice: RunApprovalChoice
    ): RunApprovalResult {
        val root = try {
            JSONObject(text)
        } catch (_: Exception) {
            throw ApprovalFailure(RunApprovalError.IncompatibleApi)
        }
        if (root.opt(OBJECT_FIELD) as? String != APPROVAL_RESPONSE_OBJECT) {
            throw ApprovalFailure(RunApprovalError.IncompatibleApi)
        }
        val runId = root.opt(RUN_ID_FIELD) as? String
            ?: throw ApprovalFailure(RunApprovalError.IncompatibleApi)
        if (runId != requestedRunId) {
            throw ApprovalFailure(RunApprovalError.IncompatibleApi)
        }
        val choice = root.opt(CHOICE_FIELD) as? String
            ?: throw ApprovalFailure(RunApprovalError.IncompatibleApi)
        if (choice != sentChoice.wireValue) {
            throw ApprovalFailure(RunApprovalError.IncompatibleApi)
        }
        val resolved = root.opt(RESOLVED_FIELD) as? Int
            ?: throw ApprovalFailure(RunApprovalError.IncompatibleApi)
        if (resolved < 1) {
            throw ApprovalFailure(RunApprovalError.IncompatibleApi)
        }
        return RunApprovalResult.Resolved(runId, sentChoice)
    }

    private fun readBoundedUtf8(body: ResponseBody?): String {
        if (body == null || body.contentLength() > MAX_RESPONSE_BYTES) {
            throw ApprovalFailure(RunApprovalError.IncompatibleApi)
        }
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_SIZE)
        body.byteStream().use { input ->
            var total = 0
            while (true) {
                val remaining = MAX_RESPONSE_BYTES + 1 - total
                if (remaining <= 0) {
                    throw ApprovalFailure(RunApprovalError.IncompatibleApi)
                }
                val read = input.read(buffer, 0, minOf(buffer.size, remaining))
                if (read == -1) break
                output.write(buffer, 0, read)
                total += read
                if (total > MAX_RESPONSE_BYTES) {
                    throw ApprovalFailure(RunApprovalError.IncompatibleApi)
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
        return text ?: throw ApprovalFailure(RunApprovalError.IncompatibleApi)
    }

    private fun isSafeRunId(runId: String): Boolean {
        if (runId.isBlank()) return false
        if (runId.length > MAX_RUN_ID_LENGTH) return false
        return runId.none { it.isISOControl() }
    }

    private fun isSafeRequestId(requestId: String): Boolean {
        if (requestId.isBlank()) return false
        if (requestId.length > MAX_REQUEST_ID_LENGTH) return false
        return requestId.none { it.isISOControl() }
    }

    private class ApprovalFailure(val reason: RunApprovalError) : Exception()

    private class NoPendingApprovalException : Exception()

    /**
     * Builds the production default approval client. Tests may keep
     * their own short timeouts; this factory is the single configurable
     * production default.
     */
    internal companion object {
        const val MAX_RESPONSE_BYTES = 512 * 1024
        const val MAX_API_KEY_CHARACTERS = 4096
        const val MAX_RUN_ID_LENGTH = 256
        const val MAX_REQUEST_ID_LENGTH = 256
        const val BUFFER_SIZE = 8 * 1024
        const val V1_SEGMENT = "v1"
        const val RUNS_SEGMENT = "runs"
        const val APPROVAL_SEGMENT = "approval"
        const val AUTHORIZATION_HEADER = "Authorization"
        const val BEARER_PREFIX = "Bearer "
        const val CONTENT_TYPE_HEADER = "Content-Type"
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
        const val JSON_MEDIA_TYPE_VALUE = "application/json"
        const val OBJECT_FIELD = "object"
        const val RUN_ID_FIELD = "run_id"
        const val CHOICE_FIELD = "choice"
        const val REQUEST_ID_FIELD = "request_id"
        const val RESOLVED_FIELD = "resolved"
        const val APPROVAL_RESPONSE_OBJECT = "hermes.run.approval_response"
        const val OK_CODE = 200
        const val NOT_FOUND_CODE = 404
        const val CONFLICT_CODE = 409
        // Documented 409 `error.code` values that mean "no pending approval".
        val PENDING_CONFLICT_CODES = setOf("approval_not_pending", "approval_not_active")
        const val RATE_LIMITED_CODE = 429

        fun defaultRunApprovalHttpClient(): OkHttpClient = OkHttpClient.Builder()
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
