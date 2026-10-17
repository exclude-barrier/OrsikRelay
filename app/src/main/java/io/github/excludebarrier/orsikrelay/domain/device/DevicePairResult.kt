package io.github.excludebarrier.orsikrelay.domain.device

/**
 * Closed result of one device pairing attempt.
 *
 * Wire contract (read-only reference, server contract v1):
 * `POST /v1/devices/pair` answers 201 with EXACTLY the field set
 * `{"object":"hermes.device.credential","device_id":<uuid>,
 * "device_key":"hd_<43 base64url>","profile":<string>,
 * "label":<string>,"permissions":[...],"issued_at":<float>}`.
 *
 * The device key is transmitted exactly once (in this 201 answer) and
 * is never emitted again by the server; it therefore must be persisted
 * locally (credential store) or it is lost forever.
 *
 * [Failure] carries a closed [DevicePairError] and never exposes the
 * pairing code, the device key, response bodies, URLs or exception
 * text. The pairing POST is single-use and is never retried
 * automatically.
 */
sealed interface DevicePairResult {

    /** The server paired the device and returned exactly the closed credential. */
    data class Paired(val credential: DeviceCredential) : DevicePairResult

    /** The pairing failed; [error] is closed and generic. */
    data class Failure(val error: DevicePairError) : DevicePairResult
}
