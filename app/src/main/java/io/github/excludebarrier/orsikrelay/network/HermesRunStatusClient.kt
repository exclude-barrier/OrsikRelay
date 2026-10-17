package io.github.excludebarrier.orsikrelay.network

import io.github.excludebarrier.orsikrelay.domain.profile.ProfileBaseUrlValidator
import io.github.excludebarrier.orsikrelay.domain.profile.SecretValue
import io.github.excludebarrier.orsikrelay.domain.run.RunServerStatus
import io.github.excludebarrier.orsikrelay.domain.run.RunStatusError
import io.github.excludebarrier.orsikrelay.domain.run.RunStatusResult
import io.github.excludebarrier.orsikrelay.domain.run.RunStatusTransport
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import org.json.JSONObject

/**
 * Performs the single authenticated `GET /v1/runs/{run_id}` read.
 * This class is the transport only; profile lookup and credential access
 * happen in the repository layer, so the key never reaches this class
 * except as the scoped [SecretValue] argument.
 *
 * Wire contract (derived read-only from the installed server code
 * `gateway/platforms/api_server_runs.py`, `_handle_get_run` /
 * `_load_owned_run`):
 * - the handler reads no request body and answers 200 with the pollable
 *   run status object `{"object":"hermes.run","run_id":<id>,"status":<s>,
 *   ...}` for exactly the owned run;
 * - `status` is one of the closed values `queued`, `running`,
 *   `waiting_for_approval`, `stopping`, `completed`, `failed`,
 *   `cancelled`, `interrupted` (`TERMINAL_STATUSES` in
 *   `api_server_run_idempotency.py`: the last four are terminal);
 * - a foreign/unknown/expired run answers 404 `run_not_found`;
 * - every other field of the status object (`output`, `error`, `usage`,
 *   `session_id`, `model`, `pending_steer`, …) is deliberately neither
 *   parsed nor propagated;
 * - an unknown `status` string is a closed protocol violation
 *   ([RunStatusError.IncompatibleApi]), never a guessed state.
 *
 * Security:
 * - HTTPS base URLs only (validated by [ProfileBaseUrlValidator]); an
 *   existing path prefix of the base URL is preserved;
 * - the run ID is added as exactly one encoded path segment via
 *   `addPathSegment`;
 * - the bearer key is only ever materialized inside [SecretValue.useRaw]
 *   and placed in the `Authorization` header;
 * - redirects and connection retries are disabled; the GET is never
 *   retried automatically;
 * - only HTTP 200 with a JSON media type is accepted; the body is
 *   bounded and parsed strictly;
 * - every failure maps to a closed [RunStatusError]; no response body,
 *   exception text, URL, key or server detail ever appears in a result
 *   or log.
 */
class HermesRunStatusClient(
    okHttpClient: OkHttpClient = defaultRunStatusHttpClient()
) : RunStatusTransport {

    private val client = okHttpClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()

    override fun getRunStatus(
        baseUrl: String,
        apiKey: SecretValue,
        runId: String
    ): RunStatusResult {
        val base = ProfileBaseUrlValidator.validate(baseUrl).getOrNull()
            ?: return RunStatusResult.Failure(RunStatusError.InvalidInput)
        if (!isSafeRunId(runId)) {
            return RunStatusResult.Failure(RunStatusError.InvalidInput)
        }
        val keyIsSafe = apiKey.useRaw { rawKey ->
            rawKey.length in 1..MAX_API_KEY_CHARACTERS &&
                rawKey.none { character ->
                    character.isWhitespace() || character.isISOControl()
                }
        }
        if (!keyIsSafe) {
            return RunStatusResult.Failure(RunStatusError.InvalidInput)
        }

        // Exactly one encoded segment per path piece: the run ID is never
        // concatenated or added via `addPathSegments`.
        val url = base.toHttpUrl().newBuilder()
            .addPathSegment(V1_SEGMENT)
            .addPathSegment(RUNS_SEGMENT)
            .addPathSegment(runId)
            .build()
        val request = apiKey.useRaw { rawKey ->
            Request.Builder()
                .url(url)
                .get()
                .header(AUTHORIZATION_HEADER, "$BEARER_PREFIX$rawKey")
                .header(ACCEPT_HEADER, JSON_MEDIA_TYPE)
                .build()
        }

        return try {
            client.newCall(request).execute().use { response ->
                requireSuccess(response)
                requireJsonContentType(response)
                parseStatusAnswer(readBoundedUtf8(response.body), runId)
            }
        } catch (error: StatusFailure) {
            RunStatusResult.Failure(error.reason)
        } catch (_: SocketTimeoutException) {
            RunStatusResult.Failure(RunStatusError.Timeout)
        } catch (_: SSLException) {
            RunStatusResult.Failure(RunStatusError.Tls)
        } catch (_: InterruptedIOException) {
            RunStatusResult.Failure(RunStatusError.Timeout)
        } catch (error: IOException) {
            RunStatusResult.Failure(if (error.hasTlsFailure()) RunStatusError.Tls else RunStatusError.Network)
        } catch (_: Exception) {
            RunStatusResult.Failure(RunStatusError.IncompatibleApi)
        }
    }

    /** Only HTTP 200 continues; every other code is a closed reason. */
    private fun requireSuccess(response: Response) {
        when (response.code) {
            OK_CODE -> return
            401, 403 -> throw StatusFailure(RunStatusError.InvalidCredentials)
            NOT_FOUND_CODE -> throw StatusFailure(RunStatusError.NotFound)
            CONFLICT_CODE -> throw StatusFailure(RunStatusError.Conflict)
            RATE_LIMITED_CODE -> throw StatusFailure(RunStatusError.RateLimited)
            else -> throw StatusFailure(RunStatusError.Http)
        }
    }

    /** Only a JSON media type is accepted (parameters such as a charset are allowed). */
    private fun requireJsonContentType(response: Response) {
        val type = response.header(CONTENT_TYPE_HEADER)
            ?.substringBefore(';')
            ?.trim()
        if (!JSON_MEDIA_TYPE.equals(type, ignoreCase = true)) {
            throw StatusFailure(RunStatusError.IncompatibleApi)
        }
    }

    /**
     * Strict contract check: JSON object root, string `run_id` equal to
     * exactly the requested ID and a `status` string from the closed
     * server vocabulary. No other server field is read or propagated.
     */
    private fun parseStatusAnswer(text: String, requestedRunId: String): RunStatusResult {
        val root = try {
            JSONObject(text)
        } catch (_: Exception) {
            throw StatusFailure(RunStatusError.IncompatibleApi)
        }
        val runId = root.opt(RUN_ID_FIELD) as? String
            ?: throw StatusFailure(RunStatusError.IncompatibleApi)
        if (runId != requestedRunId) {
            throw StatusFailure(RunStatusError.IncompatibleApi)
        }
        val status = root.opt(STATUS_FIELD) as? String
            ?: throw StatusFailure(RunStatusError.IncompatibleApi)
        return RunStatusResult.Known(
            when (status) {
                QUEUED_STATUS -> RunServerStatus.QUEUED
                RUNNING_STATUS -> RunServerStatus.RUNNING
                WAITING_FOR_APPROVAL_STATUS -> RunServerStatus.WAITING_FOR_APPROVAL
                STOPPING_STATUS -> RunServerStatus.STOPPING
                COMPLETED_STATUS -> RunServerStatus.COMPLETED
                FAILED_STATUS -> RunServerStatus.FAILED
                CANCELLED_STATUS -> RunServerStatus.CANCELLED
                INTERRUPTED_STATUS -> RunServerStatus.INTERRUPTED
                else -> throw StatusFailure(RunStatusError.IncompatibleApi)
            }
        )
    }

    private fun readBoundedUtf8(body: ResponseBody?): String {
        if (body == null || body.contentLength() > MAX_RESPONSE_BYTES) {
            throw StatusFailure(RunStatusError.IncompatibleApi)
        }
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_SIZE)
        body.byteStream().use { input ->
            var total = 0
            while (true) {
                val remaining = MAX_RESPONSE_BYTES + 1 - total
                if (remaining <= 0) {
                    throw StatusFailure(RunStatusError.IncompatibleApi)
                }
                val read = input.read(buffer, 0, minOf(buffer.size, remaining))
                if (read == -1) break
                output.write(buffer, 0, read)
                total += read
                if (total > MAX_RESPONSE_BYTES) {
                    throw StatusFailure(RunStatusError.IncompatibleApi)
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
        return text ?: throw StatusFailure(RunStatusError.IncompatibleApi)
    }

    private fun isSafeRunId(runId: String): Boolean {
        if (runId.isBlank()) return false
        if (runId.length > MAX_RUN_ID_LENGTH) return false
        return runId.none { it.isISOControl() }
    }

    private class StatusFailure(val reason: RunStatusError) : Exception()

    internal companion object {
        const val MAX_RESPONSE_BYTES = 512 * 1024
        const val MAX_API_KEY_CHARACTERS = 4096
        const val MAX_RUN_ID_LENGTH = 256
        const val BUFFER_SIZE = 8 * 1024
        const val V1_SEGMENT = "v1"
        const val RUNS_SEGMENT = "runs"
        const val AUTHORIZATION_HEADER = "Authorization"
        const val BEARER_PREFIX = "Bearer "
        const val ACCEPT_HEADER = "Accept"
        const val CONTENT_TYPE_HEADER = "Content-Type"
        const val JSON_MEDIA_TYPE = "application/json"
        const val RUN_ID_FIELD = "run_id"
        const val STATUS_FIELD = "status"
        const val QUEUED_STATUS = "queued"
        const val RUNNING_STATUS = "running"
        const val WAITING_FOR_APPROVAL_STATUS = "waiting_for_approval"
        const val STOPPING_STATUS = "stopping"
        const val COMPLETED_STATUS = "completed"
        const val FAILED_STATUS = "failed"
        const val CANCELLED_STATUS = "cancelled"
        const val INTERRUPTED_STATUS = "interrupted"
        const val OK_CODE = 200
        const val NOT_FOUND_CODE = 404
        const val CONFLICT_CODE = 409
        const val RATE_LIMITED_CODE = 429

        fun defaultRunStatusHttpClient(): OkHttpClient = OkHttpClient.Builder()
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
