package io.github.excludebarrier.orsikrelay.data.profile

import io.github.excludebarrier.orsikrelay.domain.profile.ProfileDraft
import io.github.excludebarrier.orsikrelay.domain.profile.SecretValue
import io.github.excludebarrier.orsikrelay.domain.profile.ServerProfile
import io.github.excludebarrier.orsikrelay.security.credential.CredentialStore
import java.util.UUID

/**
 * Composes non-secret profile metadata with the separate credential store.
 *
 * [saveVerified] is intentionally the only write entry point. The current
 * onboarding flow never calls it; a later connection test will do so only
 * after health and capability checks succeed.
 */
class ServerProfileRepository(
    private val metadataStore: ServerProfileMetadataStore,
    private val credentialStore: CredentialStore,
    private val idFactory: () -> String = { UUID.randomUUID().toString() }
) {

    fun saveVerified(draft: ProfileDraft): ServerProfile {
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
    }

    private fun <T> repositoryCall(block: () -> T): T = try {
        block()
    } catch (error: ServerProfileRepositoryException) {
        throw error
    } catch (_: Exception) {
        throw ServerProfileRepositoryException()
    }
}
