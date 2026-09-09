package io.github.excludebarrier.orsikrelay.domain.profile

/**
 * Small UI seam for managing stored server profiles (Mission 010).
 *
 * The UI depends only on this interface and [ServerProfile]. The
 * production implementation is the adapter in the `data` package, which
 * delegates to the existing verified [io.github.excludebarrier.orsikrelay.data.profile.ServerProfileRepository]
 * deletion path (credential deletion, metadata deletion, draft cleanup).
 *
 * Implementations must be safe to call from `Dispatchers.IO`, must never
 * retry a deletion automatically and must never leak the API key, URLs in
 * error text or exception details.
 */
interface ProfileAdmin {
    fun listProfiles(): List<ServerProfile>
    fun deleteProfile(profileId: String)
}
