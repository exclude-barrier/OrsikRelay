package io.github.excludebarrier.orsikrelay.domain.session

import io.github.excludebarrier.orsikrelay.domain.profile.SecretValue

/**
 * Authenticated raw read seam for `GET /api/sessions` (Mission 004A).
 *
 * The production implementation is
 * [io.github.excludebarrier.orsikrelay.network.HermesSessionClient]. The repository
 * injects this seam so unit tests can verify URL, credential and result
 * propagation without a network. The key is only ever passed as the
 * scoped [SecretValue] argument and never appears in the result.
 */
fun interface SessionPageFetcher {
    fun fetchPage(
        baseUrl: String,
        apiKey: SecretValue,
        limit: Int,
        offset: Int
    ): SessionListResult
}
