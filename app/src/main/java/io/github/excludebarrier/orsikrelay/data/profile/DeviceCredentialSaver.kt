package io.github.excludebarrier.orsikrelay.data.profile

import io.github.excludebarrier.orsikrelay.domain.device.DeviceCredential
import io.github.excludebarrier.orsikrelay.domain.profile.ServerProfile

/**
 * Neutral seam that lets the profile layer persist a
 * paired-device credential WITHOUT breaking the existing API-key path.
 *
 * The device key is stored through the same
 * [io.github.excludebarrier.orsikrelay.security.credential.CredentialStore]
 * infrastructure as the API key (same protection levels:
 * Android Keystore-wrapped AES-GCM, app-private preferences). For a
 * paired device the key REPLACES the profile credential: after pairing,
 * the device key is the credential used for all subsequent requests
 * (server contract v1 sends the device key as
 * `Authorization: Bearer` in every request). The non-secret metadata is
 * persisted in the device metadata store so it survives process restarts
 * without ever touching the credential store.
 *
 * The seam is optional: a [ServerProfileRepository] constructed without
 * one keeps the existing API-key-only behavior exactly as before (device
 * credential operations are unavailable for that repository instance).
 *
 * No part of the device key may appear in metadata, logs, exceptions or
 * results.
 */
fun interface DeviceCredentialSaver {
    /**
     * Persists the paired [credential] for the given [profile].
     */
    fun saveDeviceCredential(profile: ServerProfile, credential: DeviceCredential)
}
