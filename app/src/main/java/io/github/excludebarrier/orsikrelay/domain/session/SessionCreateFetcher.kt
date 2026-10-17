package io.github.excludebarrier.orsikrelay.domain.session

import io.github.excludebarrier.orsikrelay.domain.profile.SecretValue

/**
 * Authenticated raw transport seam for `POST /api/sessions`.
 *
 * The production implementation is
 * [io.github.excludebarrier.orsikrelay.network.HermesSessionCreateClient]. The
 * repository injects this seam so unit tests can verify URL, credential
 * and result propagation without a network. The key is only ever passed
 * as the scoped [SecretValue] argument and never appears in the result.
 *
 * [title] is the already-sanitized optional title: `null` when the user
 * entered nothing (or only whitespace), otherwise trimmed and at most 512
 * characters without ISO control characters.
 */
fun interface SessionCreateFetcher {
    fun createSession(
        baseUrl: String,
        apiKey: SecretValue,
        title: String?
    ): SessionCreateResult
}
