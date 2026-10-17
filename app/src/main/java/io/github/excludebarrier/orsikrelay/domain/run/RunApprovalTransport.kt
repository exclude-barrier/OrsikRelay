package io.github.excludebarrier.orsikrelay.domain.run

import io.github.excludebarrier.orsikrelay.domain.profile.SecretValue

/**
 * Authenticated raw transport seam for
 * `POST /v1/runs/{run_id}/approval`.
 *
 * The production implementation is
 * [io.github.excludebarrier.orsikrelay.network.HermesRunApprovalClient]. The
 * repository injects this seam so unit tests can verify URL,
 * credential, choice, request ID and result propagation without a
 * network. The key is only ever passed as the scoped [SecretValue]
 * argument and never appears in the result.
 *
 * Implementations must be safe to call from `Dispatchers.IO`, must
 * never retry the POST automatically and must never leak the API key,
 * response bodies, URLs or exception text into the result.
 */
fun interface RunApprovalTransport {
    fun respondApproval(
        baseUrl: String,
        apiKey: SecretValue,
        runId: String,
        requestId: String,
        choice: RunApprovalChoice
    ): RunApprovalResult
}
