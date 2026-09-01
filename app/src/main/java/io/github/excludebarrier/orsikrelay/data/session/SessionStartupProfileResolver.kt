package io.github.excludebarrier.orsikrelay.data.session

import io.github.excludebarrier.orsikrelay.data.profile.ServerProfileMetadataStore
import io.github.excludebarrier.orsikrelay.domain.profile.ServerProfile
import io.github.excludebarrier.orsikrelay.security.credential.CredentialStore

/** Deterministic outcome of the startup profile lookup (Mission 004A). */
sealed interface StartupProfile {
    /** No profile is persisted: onboarding stays the start screen. */
    data object None : StartupProfile
    /** First deterministically stored profile ID that also has a credential. */
    data class Ready(val profileId: String) : StartupProfile
    /** Stored profiles exist but none has both metadata and credential. */
    data object Unavailable : StartupProfile
}

/**
 * Resolves the profile used after an app restart (Mission 004A).
 *
 * The metadata list is deterministic (sorted by profile ID). Metadata and
 * the credential are read separately; the first ID whose metadata and
 * credential both exist wins. Read failures are treated as generic
 * unavailability: no exception text or profile data escapes.
 *
 * Expected to run on `Dispatchers.IO`; this function performs no I/O of
 * its own.
 */
fun resolveStartupProfile(
    metadataStore: ServerProfileMetadataStore,
    credentialStore: CredentialStore
): StartupProfile {
    val profiles: List<ServerProfile> = try {
        metadataStore.list()
    } catch (_: Exception) {
        return StartupProfile.Unavailable
    }
    if (profiles.isEmpty()) return StartupProfile.None
    for (profile in profiles.sortedBy { it.id }) {
        val profileId: String = try {
            profile.id
        } catch (_: Exception) {
            continue
        }
        if (profileId.isBlank()) continue
        val credential = try {
            credentialStore.get(profileId)
        } catch (_: Exception) {
            null
        }
        if (credential != null) return StartupProfile.Ready(profileId)
    }
    return StartupProfile.Unavailable
}
