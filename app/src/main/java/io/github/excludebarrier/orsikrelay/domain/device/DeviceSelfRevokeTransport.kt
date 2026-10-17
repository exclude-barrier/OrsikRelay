package io.github.excludebarrier.orsikrelay.domain.device

import io.github.excludebarrier.orsikrelay.domain.profile.SecretValue

/**
 * Authenticated raw transport seam for
 * `POST /v1/devices/self/revoke`.
 *
 * The production implementation is
 * [io.github.excludebarrier.orsikrelay.network.HermesDeviceSelfRevokeClient]. The
 * repository injects this seam so unit tests can verify URL, credential
 * and result propagation without a network. The key is only ever passed
 * as the scoped [SecretValue] argument and never appears in the result.
 *
 * The implementation must be safe to call from `Dispatchers.IO`, must
 * never retry the POST automatically and must never leak the device
 * key, response bodies or exception text into the result.
 */
fun interface DeviceSelfRevokeTransport {
    fun revokeSelf(baseUrl: String, deviceKey: SecretValue): DeviceSelfRevokeResult
}
