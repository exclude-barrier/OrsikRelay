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
 *
 * [offset] is the number of raw server rows already loaded at the newest
 * end; `0` reads the newest page exactly as before.
 */
fun interface MessageFetcher {
    fun fetch(
        baseUrl: String,
        apiKey: SecretValue,
        sessionId: String,
        offset: Int
    ): MessageHistoryResult
}
