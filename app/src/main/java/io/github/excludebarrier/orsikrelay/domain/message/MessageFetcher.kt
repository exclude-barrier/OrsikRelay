package io.github.excludebarrier.orsikrelay.domain.message

import io.github.excludebarrier.orsikrelay.domain.profile.SecretValue

/**
 * Authenticated raw read seam for
 * `GET /api/sessions/{session_id}/messages`.
 *
 * The production implementation is
 * [io.github.excludebarrier.orsikrelay.network.HermesMessageClient]. The repository
 * injects this seam so unit tests can verify URL, credential and result
 * propagation without a network. The key is only ever passed as the
 * scoped [SecretValue] argument and never appears in the result.
 */
fun interface MessageFetcher {
    fun fetch(
        baseUrl: String,
        apiKey: SecretValue,
        sessionId: String
    ): MessageHistoryResult
}
