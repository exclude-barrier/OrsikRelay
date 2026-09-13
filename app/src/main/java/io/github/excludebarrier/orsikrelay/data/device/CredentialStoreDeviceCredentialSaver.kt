package io.github.excludebarrier.orsikrelay.data.device

import io.github.excludebarrier.orsikrelay.data.profile.DeviceCredentialSaver
import io.github.excludebarrier.orsikrelay.domain.device.DeviceCredential
import io.github.excludebarrier.orsikrelay.domain.profile.ServerProfile
import io.github.excludebarrier.orsikrelay.security.credential.CredentialStore

/**
 * Production [DeviceCredentialSaver] (Mission 017A).
 *
 * Stores the paired-device credential with the SAME protection levels as
 * the API key:
 * - the raw device key goes through the [CredentialStore]
 *   (Android Keystore-wrapped AES-GCM, app-private preferences) under the
 *   profile ID, REPLACING the profile's previous credential (the device
 *   key becomes the profile's credential after pairing);
 * - the non-secret metadata (device ID, profile, label, permissions,
 *   issued-at) goes through the [DeviceCredentialMetadataStore]
 *   (app-private SharedPreferences), which never holds the key.
 *
 * If the metadata write fails after the credential write succeeded, the
 * exact previous profile credential is restored (best effort); when none
 * existed, the new credential is deleted. No part of either key ever
 * appears in a log, exception or result.
 */
class CredentialStoreDeviceCredentialSaver(
    private val credentialStore: CredentialStore,
    private val metadataStore: DeviceCredentialMetadataStore
) : DeviceCredentialSaver {

    override fun saveDeviceCredential(profile: ServerProfile, credential: DeviceCredential) {
        // Pairing replaces the profile's current bearer credential. Preserve
        // it first so a later metadata failure cannot log the user out or
        // destroy a previously working API/device key.
        val previousCredential = try {
            credentialStore.get(profile.id)
        } catch (_: Exception) {
            throw DeviceCredentialStoreException()
        }

        try {
            credentialStore.put(profile.id, credential.deviceKey)
            metadataStore.put(
                profile.id,
                DeviceCredentialMetadata(
                    deviceId = credential.deviceId,
                    profile = credential.profile,
                    label = credential.label,
                    permissions = credential.permissions,
                    issuedAt = credential.issuedAt
                )
            )
        } catch (_: Exception) {
            // Restore the exact prior credential when one existed; only
            // delete when this pairing created the first credential.
            try {
                if (previousCredential == null) {
                    credentialStore.delete(profile.id)
                } else {
                    credentialStore.put(profile.id, previousCredential)
                }
            } catch (_: Exception) {
                // Best effort; the caller's closed boundary applies.
            }
            throw DeviceCredentialStoreException()
        }
    }
}
