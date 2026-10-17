package io.github.excludebarrier.orsikrelay.domain.run

/**
 * Closed set of approval-decision failure reasons.
 *
 * Every reason is a stable, generic code: none of them carries request
 * URLs, response bodies, exception text, profile data, run IDs,
 * request IDs or credentials.
 */
sealed interface RunApprovalError {
    /** A locally invalid input (URL, credential, run/request ID or choice) was rejected. */
    data object InvalidInput : RunApprovalError
    /** The server rejected the bearer key (HTTP 401/403). */
    data object InvalidCredentials : RunApprovalError
    /** The server answered 404: the run is unknown or not owned. */
    data object NotFound : RunApprovalError
    /** The server answered 429. No automatic retry is performed. */
    data object RateLimited : RunApprovalError
    /** The TLS handshake or certificate validation failed. */
    data object Tls : RunApprovalError
    /** The request timed out. */
    data object Timeout : RunApprovalError
    /** The network is unreachable or the connection failed. */
    data object Network : RunApprovalError
    /** Any other unexpected HTTP status (including redirects). */
    data object Http : RunApprovalError
    /** The response is not the expected `POST /v1/runs/{id}/approval` contract. */
    data object IncompatibleApi : RunApprovalError
}
