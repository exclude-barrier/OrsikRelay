package io.github.excludebarrier.orsikrelay.domain.profile

/**
 * Small UI seam for managing stored server profiles (Mission 010, extended
 * in Mission 012).
 *
 * The UI depends only on this interface and [ServerProfile]. The
 * production implementation is the adapter in the `data` package, which
 * delegates to the existing verified [io.github.excludebarrier.orsikrelay.data.profile.ServerProfileRepository]
 * deletion path (credential deletion, metadata deletion, draft cleanup).
 *
 * [selectActiveServer] (Mission 012) persists the given profile ID as the
 * active server selection. It is pure metadata: implementations must never
 * store an API key, URL, draft or chat data, must fail with a closed,
 * input-free error and must never retry automatically.
 *
 * Implementations must be safe to call from `Dispatchers.IO` and must never
 * leak the API key, URLs in error text or exception details.
 */
interface ProfileAdmin {
    fun listProfiles(): List<ServerProfile>
    fun deleteProfile(profileId: String)

    /**
     * Persists [profileId] as the active server selection (Mission 012).
     * Only metadata is written; the selection path reads no credential and
     * stores none.
     */
    fun selectActiveServer(profileId: String)
}
