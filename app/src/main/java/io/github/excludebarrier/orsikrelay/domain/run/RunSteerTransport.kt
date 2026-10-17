package io.github.excludebarrier.orsikrelay.domain.run

import io.github.excludebarrier.orsikrelay.domain.profile.SecretValue

/**
 * Authenticated raw transport seam for
 * `POST /v1/runs/{run_id}/steer`.
 *
 * The production implementation is
 * [io.github.excludebarrier.orsikrelay.network.HermesRunSteerClient]. The repository
 * injects this seam so unit tests can verify URL, credential, text and
 * result propagation without a network. The key is only ever passed as
 * the scoped [SecretValue] argument and never appears in the result.
 *
 * Implementations must be safe to call from `Dispatchers.IO`, must
 * never retry the POST automatically (the steer is a non-idempotent
 * write) and must never leak the API key, steer text, response bodies
 * or exception text into the result.
 */
fun interface RunSteerTransport {
    fun steerRun(
        baseUrl: String,
        apiKey: SecretValue,
        runId: String,
        text: String
    ): RunSteerResult
}
