package io.github.excludebarrier.orsikrelay.domain.run

import io.github.excludebarrier.orsikrelay.domain.profile.SecretValue

/**
 * Authenticated raw transport seam for `GET /v1/runs/{run_id}`.
 *
 * The production implementation is
 * [io.github.excludebarrier.orsikrelay.network.HermesRunStatusClient]. The repository
 * injects this seam so unit tests can verify URL, credential and result
 * propagation without a network. The key is only ever passed as the
 * scoped [SecretValue] argument and never appears in a result.
 *
 * Implementations must be safe to call from `Dispatchers.IO`, must never
 * retry automatically (a manual reconciliation is a user action) and
 * must never leak the API key, response bodies or exception text into
 * the result. The GET is a pure read of server-side run state; it never
 * changes the run.
 */
fun interface RunStatusTransport {
    fun getRunStatus(
        baseUrl: String,
        apiKey: SecretValue,
        runId: String
    ): RunStatusResult
}
