package io.github.excludebarrier.orsikrelay.data.device

/**
 * UI-free seam (Mission 017A) for persisting the NON-SECRET metadata of
 * a paired device per profile.
 *
 * The device key is deliberately absent: it is stored only through the
 * [io.github.excludebarrier.orsikrelay.security.credential.CredentialStore] with the
 * same protection levels as the API key. Implementations must never
 * surface the key, profile IDs or underlying exceptions.
 */
interface DeviceCredentialMetadataStore {
    fun put(profileId: String, metadata: DeviceCredentialMetadata)
    fun get(profileId: String): DeviceCredentialMetadata?
    fun delete(profileId: String)
}
