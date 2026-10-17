package io.github.excludebarrier.orsikrelay.domain.device

/**
 * Unauthenticated raw transport seam for
 * `POST /v1/devices/pair`.
 *
 * The production implementation is
 * [io.github.excludebarrier.orsikrelay.network.HermesDevicePairClient]. The repository
 * injects this seam so unit tests can verify URL, code normalization
 * and result propagation without a network.
 *
 * The pairing code is a SINGLE-USE secret: the implementation must
 * never retry the POST automatically, must never follow redirects, and
 * must never echo the code into a result or log (it appears only in the
 * request body of the single request).
 */
fun interface DevicePairTransport {
    fun pairDevice(baseUrl: String, pairingCode: String): DevicePairResult
}
