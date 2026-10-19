package io.github.excludebarrier.orsikrelay.network

import io.github.excludebarrier.orsikrelay.domain.profile.ProfileBaseUrlValidator
import io.github.excludebarrier.orsikrelay.domain.profile.SecretValue
import io.github.excludebarrier.orsikrelay.domain.run.RunStopError
import io.github.excludebarrier.orsikrelay.domain.run.RunStopResult
import io.github.excludebarrier.orsikrelay.domain.run.RunStopTransport
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject

/**
 * Performs the single authenticated
 * `POST /v1/runs/{run_id}/stop` write. This class is the
 * transport only; profile lookup and credential access happen in the
 * repository layer, so the key never reaches this class except as the
 * scoped [SecretValue] argument.
 *
 * Wire contract (derived read-only from the installed server code
 * `gateway/platforms/api_server_runs.py`, `_handle_stop_run` /
 * `_load_owned_run`):
 * - the handler never reads a request body, so the request carries the
 *   exact empty JSON object `{}` and no `Content-Type` header;
 * - the accepted answer is `{"run_id": <id>, "status": "stopping"}` for
 *   exactly the requested run;
 * - an already-settled run answers with its pollable status object and
 *   one of the server's terminal statuses (`TERMINAL_STATUSES` in
 *   `api_server_run_idempotency.py`: `completed`, `failed`, `cancelled`,
 *   `interrupted`);
 * - ownership and auth failures answer 404 / 401-403, an inactive run
 *   409.
 *
 * Security:
 * - HTTPS base URLs only (validated by [ProfileBaseUrlValidator]); an
 *   existing path prefix of the base URL is preserved;
 * - the run ID is added as exactly one encoded path segment via
 *   `addPathSegment`;
 * - the bearer key is only ever materialized inside
 *   [SecretValue.useRaw] and placed in the `Authorization` header;
 * - redirects and connection retries are disabled; the POST is never
 *   repeated automatically;
 * - only HTTP 200 with a JSON media type is accepted; the body is
 *   bounded and parsed strictly;
 * - every failure maps to a closed [RunStopError]; no response body,
 *   exception text, URL, key or server detail ever appears in a result
 *   or log.
 */
class HermesRunStopClient(
    okHttpClient: OkHttpClient = HermesHttp.defaultClient()
) : RunStopTransport {

    private val client = okHttpClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()

    override fun stopRun(
        baseUrl: String,
        apiKey: SecretValue,
        runId: String
    ): RunStopResult {
        val base = ProfileBaseUrlValidator.validate(baseUrl).getOrNull()
            ?: return RunStopResult.Failure(RunStopError.InvalidInput)
        if (!isSafeOpaqueId(runId)) {
            return RunStopResult.Failure(RunStopError.InvalidInput)
        }
        if (!isSafeApiKey(apiKey)) {
            return RunStopResult.Failure(RunStopError.InvalidInput)
        }

        // Exactly one encoded segment per path piece: the run ID is never
        // concatenated or added via `addPathSegments`.
        val url = base.toHttpUrl().newBuilder()
            .addPathSegment(V1_SEGMENT)
            .addPathSegment(RUNS_SEGMENT)
            .addPathSegment(runId)
            .addPathSegment(STOP_SEGMENT)
            .build()
        // The server handler never reads a request body: the exact empty
        // JSON object goes on the wire and no JSON content type is
        // declared.
        val body = EMPTY_BODY.toByteArray().toRequestBody()
        val request = apiKey.useRaw { rawKey ->
            Request.Builder()
                .url(url)
                .post(body)
                .header(AUTHORIZATION_HEADER, "$BEARER_PREFIX$rawKey")
                .build()
        }

        return try {
            client.newCall(request).execute().use { response ->
                requireSuccess(response)
                requireJsonContentType(response) {
                    throw StopFailure(RunStopError.IncompatibleApi)
                }
                parseStopAnswer(
                    readBoundedUtf8(response.body, MAX_RESPONSE_BYTES) {
                        throw StopFailure(RunStopError.IncompatibleApi)
                    },
                    runId
                )
            }
        } catch (error: StopFailure) {
            RunStopResult.Failure(error.reason)
        } catch (_: SocketTimeoutException) {
            RunStopResult.Failure(RunStopError.Timeout)
        } catch (_: SSLException) {
            RunStopResult.Failure(RunStopError.Tls)
        } catch (_: InterruptedIOException) {
            RunStopResult.Failure(RunStopError.Timeout)
        } catch (error: IOException) {
            RunStopResult.Failure(if (error.hasTlsFailure()) RunStopError.Tls else RunStopError.Network)
        } catch (_: Exception) {
            RunStopResult.Failure(RunStopError.IncompatibleApi)
        }
    }

    /** Only HTTP 200 continues; every other code is a closed reason. */
    private fun requireSuccess(response: Response) {
        when (response.code) {
            OK_CODE -> return
            401, 403 -> throw StopFailure(RunStopError.InvalidCredentials)
            NOT_FOUND_CODE -> throw StopFailure(RunStopError.NotFound)
            CONFLICT_CODE -> throw StopFailure(RunStopError.Conflict)
            RATE_LIMITED_CODE -> throw StopFailure(RunStopError.RateLimited)
            else -> throw StopFailure(RunStopError.Http)
        }
    }

    /**
     * Strict contract check of the stop answer: JSON object root,
     * string `run_id` equal to exactly the requested ID and string
     * `status`. `stopping` is the accepted stop; one of the server's
     * terminal statuses is an already-terminal success; every other
     * value is a closed protocol violation. No other server field is
     * read or propagated.
     */
    private fun parseStopAnswer(text: String, requestedRunId: String): RunStopResult {
        val root = try {
            JSONObject(text)
        } catch (_: Exception) {
            throw StopFailure(RunStopError.IncompatibleApi)
        }
        val runId = root.opt(RUN_ID_FIELD) as? String
            ?: throw StopFailure(RunStopError.IncompatibleApi)
        if (runId != requestedRunId) {
            throw StopFailure(RunStopError.IncompatibleApi)
        }
        val status = root.opt(STATUS_FIELD) as? String
            ?: throw StopFailure(RunStopError.IncompatibleApi)
        return when (status) {
            STOPPING_STATUS -> RunStopResult.Accepted(runId)
            in TERMINAL_STATUSES -> RunStopResult.AlreadyTerminal(runId)
            else -> throw StopFailure(RunStopError.IncompatibleApi)
        }
    }

    private class StopFailure(val reason: RunStopError) : Exception()

    /**
     * Builds the production default stop client. Tests may keep their
     * own short timeouts; this factory is the single configurable
     * production default.
     */
    internal companion object {
        const val MAX_RESPONSE_BYTES = 512 * 1024
        const val V1_SEGMENT = "v1"
        const val RUNS_SEGMENT = "runs"
        const val STOP_SEGMENT = "stop"
        const val AUTHORIZATION_HEADER = "Authorization"
        const val BEARER_PREFIX = "Bearer "
        const val EMPTY_BODY = "{}"
        const val RUN_ID_FIELD = "run_id"
        const val STATUS_FIELD = "status"
        const val STOPPING_STATUS = "stopping"
        val TERMINAL_STATUSES = setOf(
            "completed",
            "failed",
            "cancelled",
            "interrupted"
        )
        const val OK_CODE = 200
        const val NOT_FOUND_CODE = 404
        const val CONFLICT_CODE = 409
        const val RATE_LIMITED_CODE = 429
    }
}
