package io.github.excludebarrier.orsikrelay.data.profile

import io.github.excludebarrier.orsikrelay.data.device.DeviceCredentialMetadataStore
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
 * [delete] removes the profile's ordinary chat-composer
 * drafts only after credential deletion and metadata deletion have both
 * succeeded. If either earlier step fails, the drafts are preserved and the
 * existing fixed, input-free [ServerProfileRepositoryException] boundary
 * holds. The optional [profileDraftClearer] is the narrow seam the chat-draft
 * repository plugs into without the profile repository depending on the UI
 * layer.
 *
 * [selectActiveServer] persists the active server selection
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
    private val deviceCredentialSaver: DeviceCredentialSaver? = null,
    private val deviceCredentialMetadataStore: DeviceCredentialMetadataStore? = null,
    private val runApprovalCapabilityStore: RunApprovalCapabilityStore? = null
) : VerifiedProfileSaver, PairedProfileSaver {

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

    override fun saveVerifiedPaired(
        draft: ProfileDraft,
        credential: DeviceCredential
    ): ServerProfile {
        val saver = deviceCredentialSaver ?: throw ServerProfileRepositoryException()
        val deviceMetadata = deviceCredentialMetadataStore
            ?: throw ServerProfileRepositoryException()
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
            saver.saveDeviceCredential(profile, credential)
            metadataStore.save(profile)
        } catch (_: Exception) {
            try {
                credentialStore.delete(profile.id)
            } catch (_: Exception) {
                // Best effort; retain the generic transaction failure.
            }
            try {
                deviceMetadata.delete(profile.id)
            } catch (_: Exception) {
                // Best effort; retain the generic transaction failure.
            }
            try {
                metadataStore.delete(profile.id)
            } catch (_: Exception) {
                // Handles stores that committed and then reported failure.
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

    /**
     * Non-secret credential-kind probe.
     *
     * Returns true iff the profile exists AND a non-secret device
     * metadata record exists for it. It NEVER reads the credential:
     * the device key stays sealed in the credential store. A missing
     * device metadata store (no pairing support wired) or any store
     * failure reports false, so the UI simply offers no device action.
     */
    fun hasDeviceCredential(profileId: String): Boolean = repositoryCall {
        if (metadataStore.get(profileId) == null) return@repositoryCall false
        val deviceMetadata = deviceCredentialMetadataStore
            ?: return@repositoryCall false
        deviceMetadata.get(profileId) != null
    }

    fun delete(profileId: String) {
        repositoryCall {
            credentialStore.delete(profileId)
            metadataStore.delete(profileId)
        }
        try {
            deviceCredentialMetadataStore?.delete(profileId)
        } catch (_: Exception) {
            // Best-effort cleanup of non-secret metadata after profile deletion.
        }
        try {
            // The per-profile approval capability is
            // non-secret metadata; without this it would leak one key per
            // deleted profile forever. Best-effort like the device metadata.
            runApprovalCapabilityStore?.clear(profileId)
        } catch (_: Exception) {
            // Best-effort cleanup; must not turn the deletion into a failure.
        }
        // Only reachable after BOTH deletions succeeded: the profile is
        // gone, so its drafts may be removed. A failure in either deletion
        // already threw above and the drafts are preserved.
        val draftCleanupFailed = if (profileDraftClearer == null) {
            false
        } else {
            try {
                profileDraftClearer.clearProfile(profileId)
                false
            } catch (_: Exception) {
                // Best-effort cleanup (profile-deletion contract): the profile is
                // already deleted, so a cleanup problem must not turn the
                // deletion into a failure or expose an underlying exception.
                // A failed cleanup counts as a prior failure: the selection of
                // the deleted profile is NOT removed below (see above),
                // so the next startup deterministically falls back to the
                // safe startup resolution instead of a stale pointer.
                true
            }
        }
        // The persisted selection of the DELETED profile is
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
     * Persists [profileId] as the active server selection.
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
     * through the optional [deviceCredentialSaver].
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
