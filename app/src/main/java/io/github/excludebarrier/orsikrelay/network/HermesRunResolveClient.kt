package io.github.excludebarrier.orsikrelay.network

import io.github.excludebarrier.orsikrelay.domain.profile.ProfileBaseUrlValidator
import io.github.excludebarrier.orsikrelay.domain.profile.SecretValue
import io.github.excludebarrier.orsikrelay.domain.run.LostRun
import io.github.excludebarrier.orsikrelay.domain.run.RunResolveError
import io.github.excludebarrier.orsikrelay.domain.run.RunResolveResult
import io.github.excludebarrier.orsikrelay.domain.run.RunResolveTransport
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * `POST /v1/runs/{run_id}/resolve-unknown` with exactly
 * `{"admission_id": …, "execution_generation": …}`.
 *
 * HTTPS only, no redirects, no connection retry and never repeated; a
 * bounded answer and closed results without key, URL, ID or body text.
 * 200 for the same run is [RunResolveResult.Resolved]; the core's
 * `stale_generation` is [RunResolveError.Stale]; everything else fails.
 */
class HermesRunResolveClient(
    okHttpClient: OkHttpClient = HermesHttp.defaultClient()
) : RunResolveTransport {

    private val client = okHttpClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()

    override fun resolve(baseUrl: String, apiKey: SecretValue, runId: String, lostRun: LostRun): RunResolveResult {
        val base = ProfileBaseUrlValidator.validate(baseUrl).getOrNull() ?: return failed()
        if (!isSafeApiKey(apiKey) || !isSafeOpaqueId(runId) || !isSafeOpaqueId(lostRun.admissionId) ||
            lostRun.generation < 0
        ) {
            return failed()
        }
        val url = base.toHttpUrl().newBuilder()
            .addPathSegment("v1")
            .addPathSegment("runs")
            .addPathSegment(runId)
            .addPathSegment("resolve-unknown")
            .build()
        val body = JSONObject()
            .put("admission_id", lostRun.admissionId)
            .put("execution_generation", lostRun.generation)
            .toString()
            .toByteArray()
            .toRequestBody(JSON)
        val request = apiKey.useRaw { rawKey ->
            Request.Builder().url(url).post(body).header("Authorization", "Bearer $rawKey").build()
        }
        return try {
            client.newCall(request).execute().use { response ->
                val text = readBoundedUtf8(response.body, MAX_RESPONSE_BYTES) { return failed() }
                when {
                    response.code == 200 && JSONObject(text).opt("run_id") == runId -> RunResolveResult.Resolved
                    extractErrorCode(text) == "stale_generation" -> RunResolveResult.Failure(RunResolveError.Stale)
                    else -> failed()
                }
            }
        } catch (_: Exception) {
            failed()
        }
    }

    private fun failed() = RunResolveResult.Failure(RunResolveError.Failed)

    private companion object {
        const val MAX_RESPONSE_BYTES = 64 * 1024
        val JSON = "application/json".toMediaType()
    }
}
