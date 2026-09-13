package io.github.excludebarrier.orsikrelay.domain.device

/**
 * Closed set of device self-revocation failure reasons (Mission 017A).
 *
 * Every reason is a stable, generic code: none of them carries the
 * device key, response bodies, URLs or exception text.
 */
sealed interface DeviceSelfRevokeError {
    /** A locally invalid input (base URL or device key) was rejected. */
    data object InvalidInput : DeviceSelfRevokeError

    /** The server answered 401 `gateway_auth_failed`: the device key is invalid or revoked. */
    data object InvalidCredentials : DeviceSelfRevokeError

    /** The server answered 403. */
    data object Forbidden : DeviceSelfRevokeError

    /** The server answered 404. */
    data object NotFound : DeviceSelfRevokeError

    /** The server answered 429. */
    data object RateLimited : DeviceSelfRevokeError

    /** The TLS handshake or certificate validation failed. */
    data object Tls : DeviceSelfRevokeError

    /** The request timed out. */
    data object Timeout : DeviceSelfRevokeError

    /** The network is unreachable or the connection failed. */
    data object Network : DeviceSelfRevokeError

    /** Any other unexpected HTTP status (including redirects). */
    data object Http : DeviceSelfRevokeError

    /** The answer is not the expected `POST /v1/devices/self/revoke` contract. */
    data object IncompatibleApi : DeviceSelfRevokeError
}
