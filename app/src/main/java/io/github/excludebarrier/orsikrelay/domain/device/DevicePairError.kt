package io.github.excludebarrier.orsikrelay.domain.device

/**
 * Closed set of device-pairing failure reasons (Mission 017A).
 *
 * The first five values are the server's closed `error.code` values for
 * `POST /v1/devices/pair` (server contract v1, read-only reference); the
 * remaining values are the transport/protocol reasons established by the
 * run-stop/approval clients.
 *
 * Every reason is a stable, generic code: none of them carries the
 * pairing code, the device key, response bodies, URLs or exception
 * text. Unknown server `error.code` values are NOT modelled: they fail
 * closed as [IncompatibleApi] (see the client parser), never as a
 * silently adopted value.
 */
sealed interface DevicePairError {
    /** A locally invalid input (base URL or pairing code) was rejected before any request. */
    data object InvalidInput : DevicePairError

    /** The server answered 400 `pairing_code_invalid`: the code is invalid, expired or already used. */
    data object PairingCodeInvalid : DevicePairError

    /** The server answered 400 `invalid_pair_request`: the request was not an acceptable pairing request. */
    data object InvalidPairRequest : DevicePairError

    /** The server answered 409 `device_limit_exceeded`: the profile already has too many active devices. */
    data object DeviceLimitExceeded : DevicePairError

    /** The server answered 429 `pair_rate_limited`. No automatic retry is performed (single-use code). */
    data object PairRateLimited : DevicePairError

    /** The server answered 500 `pair_failed`. No automatic retry is performed (single-use code). */
    data object PairFailed : DevicePairError

    /** The server answered 401/403. */
    data object InvalidCredentials : DevicePairError

    /** The server answered 404. */
    data object NotFound : DevicePairError

    /** The TLS handshake or certificate validation failed. */
    data object Tls : DevicePairError

    /** The request timed out. */
    data object Timeout : DevicePairError

    /** The network is unreachable or the connection failed. */
    data object Network : DevicePairError

    /** Any other unexpected HTTP status (including redirects). */
    data object Http : DevicePairError

    /**
     * The answer is not the expected `POST /v1/devices/pair` contract:
     * unknown/extra/missing 201 fields, a wrong `object` discriminator, a
     * malformed or overlong device key, unknown error codes, oversized or
     * non-JSON bodies. Fail-closed; never a silent adoption.
     */
    data object IncompatibleApi : DevicePairError
}
