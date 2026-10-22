package io.github.excludebarrier.orsikrelay.data.session

import io.github.excludebarrier.orsikrelay.data.profile.ServerProfileRepository
import io.github.excludebarrier.orsikrelay.domain.profile.SecretValue
import io.github.excludebarrier.orsikrelay.domain.session.SessionManageError
import io.github.excludebarrier.orsikrelay.domain.session.SessionManageResult
import io.github.excludebarrier.orsikrelay.domain.session.SessionManageTransport
import io.github.excludebarrier.orsikrelay.domain.session.SessionManager
import io.github.excludebarrier.orsikrelay.network.HermesSessionManageClient
import io.github.excludebarrier.orsikrelay.security.credential.CredentialStore

/**
 * Production [SessionManager]: loads the profile and its credential
 * separately and hands the single request to [transport]. A missing
 * profile or credential fails closed; nothing is retried.
 */
class SessionManageRepository(
    private val profileRepository: ServerProfileRepository,
    private val credentialStore: CredentialStore,
    private val transport: SessionManageTransport = HermesSessionManageClient()
) : SessionManager {

    override fun supportsManagement(profileId: String): Boolean =
        withTarget(profileId, false) { baseUrl, key -> transport.supportsManagement(baseUrl, key) }

    override fun setArchived(profileId: String, sessionId: String, archived: Boolean): SessionManageResult =
        withTarget(profileId, FAILED) { baseUrl, key -> transport.setArchived(baseUrl, key, sessionId, archived) }

    override fun delete(profileId: String, sessionId: String): SessionManageResult =
        withTarget(profileId, FAILED) { baseUrl, key -> transport.delete(baseUrl, key, sessionId) }

    private inline fun <T> withTarget(profileId: String, fallback: T, block: (String, SecretValue) -> T): T {
        if (profileId.isBlank()) return fallback
        val profile = try {
            profileRepository.get(profileId)
        } catch (_: Exception) {
            null
        } ?: return fallback
        val credential = try {
            credentialStore.get(profileId)
        } catch (_: Exception) {
            null
        }
        if (credential == null || credential.isEmpty()) return fallback
        return try {
            block(profile.baseUrl, credential)
        } catch (_: Exception) {
            fallback
        }
    }

    private companion object {
        val FAILED = SessionManageResult.Failure(SessionManageError.Failed)
    }
}
