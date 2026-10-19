package io.github.excludebarrier.orsikrelay.data.message

import io.github.excludebarrier.orsikrelay.data.profile.ServerProfileRepository
import io.github.excludebarrier.orsikrelay.domain.message.MessageFetcher
import io.github.excludebarrier.orsikrelay.domain.message.MessageHistoryError
import io.github.excludebarrier.orsikrelay.domain.message.MessageHistoryResult
import io.github.excludebarrier.orsikrelay.domain.message.MessageHistorySource
import io.github.excludebarrier.orsikrelay.network.HermesMessageClient
import io.github.excludebarrier.orsikrelay.security.credential.CredentialStore

/**
 * Production [MessageHistorySource].
 *
 * Loads the profile metadata and the credential separately for the given
 * profile ID and then delegates the single authenticated read to
 * [MessageFetcher] (production: [HermesMessageClient]). Every failure
 * maps to a generic [MessageHistoryError] reason: no profile data, URL,
 * key, session ID or exception text ever escapes into the result.
 *
 * [loadOlderHistory] uses the same profile/credential path with the
 * given raw-server-row offset.
 *
 * Expected to run on `Dispatchers.IO` (SharedPreferences, Keystore and
 * network I/O).
 */
class MessageHistoryRepository(
    private val profileRepository: ServerProfileRepository,
    private val credentialStore: CredentialStore,
    private val messageFetcher: MessageFetcher = HermesMessageClient()
) : MessageHistorySource {

    override fun loadHistory(profileId: String, sessionId: String): MessageHistoryResult =
        loadPage(profileId, sessionId, offset = 0)

    override fun loadOlderHistory(
        profileId: String,
        sessionId: String,
        offset: Int
    ): MessageHistoryResult = loadPage(profileId, sessionId, offset)

    private fun loadPage(
        profileId: String,
        sessionId: String,
        offset: Int
    ): MessageHistoryResult {
        if (profileId.isBlank()) {
            return MessageHistoryResult.Failure(MessageHistoryError.MissingProfile)
        }
        if (sessionId.isBlank()) {
            return MessageHistoryResult.Failure(MessageHistoryError.InvalidInput)
        }
        val profile = try {
            profileRepository.get(profileId)
        } catch (_: Exception) {
            null
        }
        if (profile == null) {
            return MessageHistoryResult.Failure(MessageHistoryError.MissingProfile)
        }
        val credential = try {
            credentialStore.get(profileId)
        } catch (_: Exception) {
            null
        }
        if (credential == null) {
            return MessageHistoryResult.Failure(MessageHistoryError.MissingProfile)
        }
        return try {
            messageFetcher.fetch(
                baseUrl = profile.baseUrl,
                apiKey = credential,
                sessionId = sessionId,
                offset = offset
            )
        } catch (_: Exception) {
            MessageHistoryResult.Failure(MessageHistoryError.Network)
        }
    }
}
