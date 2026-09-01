package io.github.excludebarrier.orsikrelay.domain.session

/**
 * Closed set of session-list failure reasons.
 *
 * Every reason is a stable, generic code: none of them carries request
 * URLs, response bodies, exception text, profile data or credentials.
 */
sealed interface SessionListError {
    /** No profile exists or its metadata/credential is incomplete. */
    data object MissingProfile : SessionListError
    /** Stored profile metadata is not a valid HTTPS base URL. */
    data object InvalidInput : SessionListError
    /** The server rejected the bearer key. */
    data object InvalidCredentials : SessionListError
    /** The server answered 429. No automatic retry is performed. */
    data object RateLimited : SessionListError
    /** The TLS handshake or certificate validation failed. */
    data object Tls : SessionListError
    /** The request timed out. */
    data object Timeout : SessionListError
    /** The network is unreachable or the connection failed. */
    data object Network : SessionListError
    /** Any other unexpected HTTP status (including redirects). */
    data object Http : SessionListError
    /** The response is not the expected `GET /api/sessions` contract. */
    data object IncompatibleApi : SessionListError
}
