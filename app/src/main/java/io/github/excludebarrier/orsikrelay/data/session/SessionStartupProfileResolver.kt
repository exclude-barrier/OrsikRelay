package io.github.excludebarrier.orsikrelay.data.session

import io.github.excludebarrier.orsikrelay.data.profile.ActiveServerSelectionStore
import io.github.excludebarrier.orsikrelay.data.profile.ServerProfileMetadataStore
import io.github.excludebarrier.orsikrelay.domain.profile.SecretValue
import io.github.excludebarrier.orsikrelay.domain.profile.ServerProfile
import io.github.excludebarrier.orsikrelay.domain.profile.ServerSelection
import io.github.excludebarrier.orsikrelay.security.credential.CredentialStore

/** Deterministic outcome of the startup profile lookup. */
sealed interface StartupProfile {
    /** No profile is persisted: onboarding stays the start screen. */
    data object None : StartupProfile
    /** First deterministically stored profile ID that also has a credential. */
    data class Ready(val profileId: String) : StartupProfile
    /** Stored profiles exist but none has both metadata and credential. */
    data object Unavailable : StartupProfile
}

/**
 * Resolves the profile used after an app restart.
 *
 * A persisted active server selection (validated against metadata AND
 * credential) wins when present. Otherwise the metadata list is consulted
 * deterministically (sorted by profile ID); the first ID whose metadata and
 * credential both exist wins. Read failures are treated as generic
 * unavailability: no exception text or profile data escapes.
 *
 * Expected to run on `Dispatchers.IO`; this function performs no I/O of
 * its own.
 */
fun resolveStartupProfile(
    metadataStore: ServerProfileMetadataStore,
    credentialStore: CredentialStore,
    selectionStore: ActiveServerSelectionStore? = null
): StartupProfile {
    // A persisted selection that still validates against the
    // stored metadata and credential wins over the first stored profile.
    // Every invalid or unreadable selection deterministically falls through
    // to the existing resolution below.
    val selection = resolveActiveServerSelection(
        selectionStore = selectionStore ?: NoopActiveServerSelectionStore,
        metadataStore = metadataStore,
        credentialStore = credentialStore
    )
    if (selection is ServerSelection.Valid) {
        return StartupProfile.Ready(selection.profileId)
    }
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

/**
 * Validates the persisted active server selection.
 *
 * A stored ID is [ServerSelection.Valid] only when it is non-blank, still
 * present in the server metadata AND has a credential. A missing, blank,
 * deleted or inconsistent selection — or any store read failure — yields
 * [ServerSelection.Invalid] so the caller falls back to the existing safe
 * startup state. No ID, URL or error detail escapes this function.
 */
fun resolveActiveServerSelection(
    selectionStore: ActiveServerSelectionStore,
    metadataStore: ServerProfileMetadataStore,
    credentialStore: CredentialStore
): ServerSelection {
    val storedId: String = try {
        selectionStore.read().orEmpty()
    } catch (_: Exception) {
        return ServerSelection.Invalid
    }
    if (storedId.isBlank()) return ServerSelection.Invalid
    val metadata: ServerProfile? = try {
        metadataStore.get(storedId)
    } catch (_: Exception) {
        return ServerSelection.Invalid
    }
    if (metadata == null) return ServerSelection.Invalid
    val credential: SecretValue? = try {
        credentialStore.get(storedId)
    } catch (_: Exception) {
        null
    }
    return if (credential != null) {
        ServerSelection.Valid(storedId)
    } else {
        ServerSelection.Invalid
    }
}

private object NoopActiveServerSelectionStore : ActiveServerSelectionStore {
    override fun read(): String? = null
    override fun write(profileId: String) = Unit
    override fun clear() = Unit
}
