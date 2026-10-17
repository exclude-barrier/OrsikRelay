package io.github.excludebarrier.orsikrelay.data.device

/**
 * Non-secret, persisted metadata of a paired device.
 *
 * This is the metadata half of the device credential: everything the
 * 201 `POST /v1/devices/pair` answer carries EXCEPT the raw device key.
 * The key itself lives only in the
 * [io.github.excludebarrier.orsikrelay.security.credential.CredentialStore]
 * (same protection levels as the API key) and is never part of this
 * type, any store payload, log, exception or result.
 */
data class DeviceCredentialMetadata(
    val deviceId: String,
    val profile: String,
    val label: String,
    val permissions: List<String>,
    val issuedAt: Double
)
