package io.github.excludebarrier.orsikrelay.domain.run

/**
 * Closed set of run-stop failure reasons.
 *
 * Every reason is a stable, generic code: none of them carries request
 * URLs, response bodies, exception text, profile data, run IDs or
 * credentials.
 */
sealed interface RunStopError {
    /** A locally invalid input (URL, credential or run ID) was rejected. */
    data object InvalidInput : RunStopError
    /** The server rejected the bearer key (HTTP 401/403). */
    data object InvalidCredentials : RunStopError
    /** The server answered 404: the run is unknown or not owned. */
    data object NotFound : RunStopError
    /** The server answered 409: the run is not active in this gateway. */
    data object Conflict : RunStopError
    /** The server answered 429. No automatic retry is performed. */
    data object RateLimited : RunStopError
    /** The TLS handshake or certificate validation failed. */
    data object Tls : RunStopError
    /** The request timed out. */
    data object Timeout : RunStopError
    /** The network is unreachable or the connection failed. */
    data object Network : RunStopError
    /** Any other unexpected HTTP status (including redirects). */
    data object Http : RunStopError
    /** The response is not the expected `POST /v1/runs/{id}/stop` contract. */
    data object IncompatibleApi : RunStopError
}
