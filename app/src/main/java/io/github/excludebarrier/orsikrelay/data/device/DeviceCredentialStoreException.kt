package io.github.excludebarrier.orsikrelay.data.device

/**
 * Closed, generic failure of a device-credential metadata store
 * operation.
 *
 * Mirrors the established store failure pattern
 * ([io.github.excludebarrier.orsikrelay.data.profile.ProfileMetadataStoreException],
 * [io.github.excludebarrier.orsikrelay.data.profile.ActiveServerSelectionStoreException]):
 * one fixed static message, no input, no profile ID, no key, no
 * underlying exception text.
 */
class DeviceCredentialStoreException : Exception(MESSAGE) {

    companion object {
        const val MESSAGE = "Device credential metadata operation failed"
    }
}
