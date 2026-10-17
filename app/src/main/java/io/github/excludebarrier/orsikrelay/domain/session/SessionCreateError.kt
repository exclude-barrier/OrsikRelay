package io.github.excludebarrier.orsikrelay.domain.session

/**
 * Closed set of session-creation failure reasons.
 *
 * Every reason is a stable, generic code: none of them carries request
 * URLs, response bodies, exception text, profile data, titles or
 * credentials.
 */
sealed interface SessionCreateError {
    /** No profile exists or its metadata/credential is incomplete. */
    data object MissingProfile : SessionCreateError
    /** A locally invalid input (URL, credential or title) was rejected. */
    data object InvalidInput : SessionCreateError
    /** The server rejected the title with HTTP 400. */
    data object InvalidTitle : SessionCreateError
    /** The server rejected the bearer key. */
    data object InvalidCredentials : SessionCreateError
    /** The server answered 409. No automatic retry is performed. */
    data object Conflict : SessionCreateError
    /** The server answered 429. No automatic retry is performed. */
    data object RateLimited : SessionCreateError
    /** The TLS handshake or certificate validation failed. */
    data object Tls : SessionCreateError
    /** The request timed out. */
    data object Timeout : SessionCreateError
    /** The network is unreachable or the connection failed. */
    data object Network : SessionCreateError
    /** Any other unexpected HTTP status (including redirects). */
    data object Http : SessionCreateError
    /** The response is not the expected `POST /api/sessions` contract. */
    data object IncompatibleApi : SessionCreateError
}
