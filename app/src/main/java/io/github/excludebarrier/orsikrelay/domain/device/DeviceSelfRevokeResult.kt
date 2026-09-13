package io.github.excludebarrier.orsikrelay.domain.device

/**
 * Closed result of one device self-revocation attempt (Mission 017A).
 *
 * Wire contract (read-only reference, server contract v1):
 * `POST /v1/devices/self/revoke` (Bearer device key, no body) answers
 * 200 with `{"revoked":true|false,"device_id":<uuid>}`. `revoked=true`
 * is the fresh revocation; `revoked=false` is the already-revoked
 * answer. The revocation takes effect immediately (no cache): the key
 * is invalid from the very next request.
 *
 * [Failure] carries a closed [DeviceSelfRevokeError] and never exposes
 * the device key, response bodies, URLs or exception text. The
 * revocation POST is never retried automatically.
 */
sealed interface DeviceSelfRevokeResult {

    /** The server acknowledged the revocation for exactly [deviceId]. */
    data class Revoked(val revoked: Boolean, val deviceId: String?) : DeviceSelfRevokeResult

    /** The revocation failed; [error] is closed and generic. */
    data class Failure(val error: DeviceSelfRevokeError) : DeviceSelfRevokeResult
}
