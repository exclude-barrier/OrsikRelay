package io.github.excludebarrier.orsikrelay.data.profile

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
 */
class ServerProfileRepository(
    private val metadataStore: ServerProfileMetadataStore,
    private val credentialStore: CredentialStore,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    private val profileDraftClearer: ProfileDraftClearer? = null
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
        val clearer = profileDraftClearer ?: return
        try {
            // Only reachable after BOTH deletions succeeded: the profile is
            // gone, so its drafts may be removed. A failure in either
            // deletion already threw above and the drafts are preserved.
            clearer.clearProfile(profileId)
        } catch (_: Exception) {
            // Best-effort cleanup: the profile is already deleted, so a
            // cleanup problem must not turn the deletion into a failure or
            // expose an underlying exception. The production clearer absorbs
            // its own failures into its closed boundary; this keeps the
            // contract even for a non-conforming implementation.
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
