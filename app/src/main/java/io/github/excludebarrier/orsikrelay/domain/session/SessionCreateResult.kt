package io.github.excludebarrier.orsikrelay.domain.session

/**
 * Closed result of one session creation (Mission 005A).
 *
 * [Success] carries only the server-generated session ID and the optional
 * sanitized title the user entered. It never contains a URL, credential,
 * response text, server-echoed content or exception detail.
 */
sealed interface SessionCreateResult {
    data class Success(val sessionId: String, val title: String?) : SessionCreateResult
    data class Failure(val error: SessionCreateError) : SessionCreateResult
}
