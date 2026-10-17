package io.github.excludebarrier.orsikrelay.domain.device

import io.github.excludebarrier.orsikrelay.domain.profile.SecretValue

/**
 * A paired-device credential.
 *
 * Wire contract (read-only reference, server contract v1): the device is
 * introduced by the single authenticated
 * `POST /v1/devices/pair` answer
 * `{"object":"hermes.device.credential","device_id":<uuid>,
 * "device_key":"hd_<43 base64url>","profile":<string>,
 * "label":<string>,"permissions":[...],"issued_at":<float>}`.
 *
 * The raw device key is carried as a scoped [SecretValue] and is NEVER
 * part of [toString], [equals] or [hashCode]: this mirrors the
 * [io.github.excludebarrier.orsikrelay.domain.profile.ProfileDraft] pattern (key
 * printed as `[REDACTED]`) so no key can leak through logs, crash
 * reports, test output or saved state.
 *
 * `deviceKey` is deliberately NOT a plain `String` property: it is only
 * ever materialized inside [SecretValue.useRaw] at the single boundary
 * that places it into the `Authorization` header of an outgoing request.
 */
class DeviceCredential(
    val deviceId: String,
    val deviceKey: SecretValue,
    val profile: String,
    val label: String,
    val permissions: List<String>,
    val issuedAt: Double
) {

    override fun toString(): String =
        "DeviceCredential(deviceId=$deviceId, deviceKey=[REDACTED], profile=$profile," +
            " label=$label, permissions=$permissions, issuedAt=$issuedAt)"

    override fun equals(other: Any?): Boolean =
        other is DeviceCredential &&
            deviceId == other.deviceId &&
            deviceKey === other.deviceKey &&
            profile == other.profile &&
            label == other.label &&
            permissions == other.permissions &&
            issuedAt == other.issuedAt

    override fun hashCode(): Int =
        deviceId.hashCode() * 31 +
            System.identityHashCode(deviceKey) * 31 +
            profile.hashCode() * 31 +
            label.hashCode() * 31 +
            permissions.hashCode() * 31 +
            issuedAt.hashCode() * 31
}
