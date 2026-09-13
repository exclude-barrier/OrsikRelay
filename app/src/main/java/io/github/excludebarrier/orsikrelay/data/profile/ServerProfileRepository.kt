package io.github.excludebarrier.orsikrelay.data.profile

import io.github.excludebarrier.orsikrelay.domain.device.DeviceCredential
import io.github.excludebarrier.orsikrelay.domain.profile.ProfileDraft
import io.github.excludebarrier.orsikrelay.domain.profile.SecretValue
import io.github.excludebarrier.orsikrelay.domain.profile.ServerProfile
import io.github.excludebarrier.orsikrelay.security.credential.CredentialStore
import java.util.UUID

/**
 * Composes non-secret profile metadata with the separate credential store.
 *
 * [saveVerified] is intentionally the only write entry point. The onboarding
 * connection coordinator calls it only after health and capability checks
 * have succeeded.
 *
 * [delete] (Mission 009B) removes the profile's ordinary chat-composer
 * drafts only after credential deletion and metadata deletion have both
 * succeeded. If either earlier step fails, the drafts are preserved and the
 * existing fixed, input-free [ServerProfileRepositoryException] boundary
 * holds. The optional [profileDraftClearer] is the narrow seam the chat-draft
 * repository plugs into without the profile repository depending on the UI
 * layer.
 *
 * (Mission 012) [selectActiveServer] persists the active server selection
 * only for a profile whose metadata AND credential both exist, and [delete]
 * clears the persisted selection of the deleted profile only after
 * credential deletion, metadata deletion and the draft cleanup have all
 * succeeded — any earlier failure preserves the selection.
 */
class ServerProfileRepository(
    private val metadataStore: ServerProfileMetadataStore,
    private val credentialStore: CredentialStore,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    private val profileDraftClearer: ProfileDraftClearer? = null,
    private val selectionStore: ActiveServerSelectionStore? = null,
    private val deviceCredentialSaver: DeviceCredentialSaver? = null
) : VerifiedProfileSaver {

    override fun saveVerified(draft: ProfileDraft): ServerProfile {
        val profile = try {
            ServerProfile.create(
                id = idFactory(),
                profileName = draft.profileName,
                baseUrl = draft.baseUrl
            )
        } catch (_: Exception) {
            throw ServerProfileRepositoryException()
        }

        try {
            credentialStore.put(profile.id, draft.apiKey)
        } catch (_: Exception) {
            throw ServerProfileRepositoryException()
        }

        try {
            metadataStore.save(profile)
        } catch (_: Exception) {
            try {
                credentialStore.delete(profile.id)
            } catch (_: Exception) {
                // Best effort: preserve the original generic failure boundary.
            }
            throw ServerProfileRepositoryException()
        }
        return profile
    }

    fun get(profileId: String): ServerProfile? = repositoryCall {
        metadataStore.get(profileId)
    }

    fun list(): List<ServerProfile> = repositoryCall {
        metadataStore.list()
    }

    fun credentialFor(profileId: String): SecretValue? = repositoryCall {
        credentialStore.get(profileId)
    }

    fun delete(profileId: String) {
        repositoryCall {
            credentialStore.delete(profileId)
            metadataStore.delete(profileId)
        }
        // Only reachable after BOTH deletions succeeded: the profile is
        // gone, so its drafts may be removed. A failure in either deletion
        // already threw above and the drafts are preserved.
        val draftCleanupFailed = if (profileDraftClearer == null) {
            false
        } else {
            try {
                profileDraftClearer!!.clearProfile(profileId)
                false
            } catch (_: Exception) {
                // Best-effort cleanup (Mission 009B contract): the profile is
                // already deleted, so a cleanup problem must not turn the
                // deletion into a failure or expose an underlying exception.
                // A failed cleanup counts as a Vorfehler: the selection of
                // the deleted profile is NOT removed below (see Mission 012),
                // so the next startup deterministically falls back to the
                // safe startup resolution instead of a stale pointer.
                true
            }
        }
        // Mission 012: the persisted selection of the DELETED profile is
        // removed only after credential, metadata AND draft cleanup have all
        // succeeded. Deleting a profile that is not the selected one leaves
        // the selection untouched; the startup validation covers every other
        // case. A draft-cleanup failure preserves the selection.
        if (draftCleanupFailed) return
        val selectionStore = this.selectionStore ?: return
        val storedSelection = try {
            selectionStore.read()
        } catch (_: Exception) {
            null
        }
        if (storedSelection != profileId) return
        repositoryCall {
            selectionStore.clear()
        }
    }

    /**
     * Persists [profileId] as the active server selection (Mission 012).
     *
     * The selection is private local metadata: exactly the profile ID. It is
     * persisted only when the profile exists (metadata) AND has a
     * credential, checked in that fixed order before any write. The
     * selection path reads no secret value and writes none.
     */
    fun selectActiveServer(profileId: String) {
        repositoryCall {
            if (metadataStore.get(profileId) == null) {
                throw ServerProfileRepositoryException()
            }
            if (credentialStore.get(profileId) == null) {
                throw ServerProfileRepositoryException()
            }
            val store = selectionStore ?: throw ServerProfileRepositoryException()
            store.write(profileId)
        }
    }

    /**
     * Persists a paired-device credential for an existing profile
     * (Mission 017A) through the optional [deviceCredentialSaver].
     *
     * The saver stores the device key through the same credential store
     * as the API key (the device key REPLACES the profile credential
     * after pairing) and the non-secret metadata through the device
     * metadata store. The profile must exist; a missing profile, a
     * missing saver, or a saver failure all report the existing closed,
     * input-free [ServerProfileRepositoryException]. The existing
     * API-key-only path is untouched when no saver is installed.
     */
    fun saveDeviceCredential(profileId: String, credential: DeviceCredential) {
        repositoryCall {
            val saver = deviceCredentialSaver ?: throw ServerProfileRepositoryException()
            val profile = metadataStore.get(profileId)
                ?: throw ServerProfileRepositoryException()
            saver.saveDeviceCredential(profile, credential)
        }
    }

    private fun <T> repositoryCall(block: () -> T): T = try {
        block()
    } catch (error: ServerProfileRepositoryException) {
        throw error
    } catch (_: Exception) {
        throw ServerProfileRepositoryException()
    }
}
