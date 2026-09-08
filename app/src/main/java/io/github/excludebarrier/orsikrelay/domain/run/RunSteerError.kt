package io.github.excludebarrier.orsikrelay.domain.run

/**
 * Closed set of run-steer failure reasons (Mission 008A).
 *
 * Every reason is a stable, generic code: none of them carries request
 * URLs, response bodies, exception text, profile data, run IDs, steer
 * text or credentials.
 */
sealed interface RunSteerError {
    /** A locally invalid input (URL, credential, run ID or text) was rejected. */
    data object InvalidInput : RunSteerError
    /** The server rejected the bearer key (HTTP 401/403). */
    data object InvalidCredentials : RunSteerError
    /** The server answered 404: the run is unknown or not owned. */
    data object NotFound : RunSteerError
    /** The server answered 429. No automatic retry is performed. */
    data object RateLimited : RunSteerError
    /** The TLS handshake or certificate validation failed. */
    data object Tls : RunSteerError
    /** The request timed out. */
    data object Timeout : RunSteerError
    /** The network is unreachable or the connection failed. */
    data object Network : RunSteerError
    /** Any other unexpected HTTP status (including redirects). */
    data object Http : RunSteerError
    /** The response is not the expected `POST /v1/runs/{id}/steer` contract. */
    data object IncompatibleApi : RunSteerError
}
