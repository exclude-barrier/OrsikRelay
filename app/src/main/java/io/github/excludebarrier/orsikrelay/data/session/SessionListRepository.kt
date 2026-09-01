package io.github.excludebarrier.orsikrelay.data.session

import io.github.excludebarrier.orsikrelay.data.profile.ServerProfileRepository
import io.github.excludebarrier.orsikrelay.domain.profile.ProfileBaseUrlValidator
import io.github.excludebarrier.orsikrelay.domain.session.SessionListError
import io.github.excludebarrier.orsikrelay.domain.session.SessionListResult
import io.github.excludebarrier.orsikrelay.domain.session.SessionPageFetcher
import io.github.excludebarrier.orsikrelay.domain.session.SessionPageSource
import io.github.excludebarrier.orsikrelay.network.HermesSessionClient
import io.github.excludebarrier.orsikrelay.security.credential.CredentialStore

/**
 * Production [SessionPageSource] (Mission 004A).
 *
 * Loads the profile metadata and the credential separately for the given
 * profile ID and then delegates the single authenticated read to
 * [SessionPageFetcher] (production: [HermesSessionClient]). Every failure
 * maps to a generic [SessionListError] reason: no profile data, URL, key
 * or exception text ever escapes into the result.
 *
 * Expected to run on `Dispatchers.IO` (SharedPreferences, Keystore and
 * network I/O).
 */
class SessionListRepository(
    private val profileRepository: ServerProfileRepository,
    private val credentialStore: CredentialStore,
    private val pageFetcher: SessionPageFetcher = HermesSessionClient()
) : SessionPageSource {

    override fun fetchPage(
        profileId: String,
        limit: Int,
        offset: Int
    ): SessionListResult {
        if (profileId.isBlank()) {
            return SessionListResult.Failure(SessionListError.MissingProfile)
        }
        val profile = try {
            profileRepository.get(profileId)
        } catch (_: Exception) {
            null
        }
        if (profile == null) {
            return SessionListResult.Failure(SessionListError.MissingProfile)
        }
        val credential = try {
            credentialStore.get(profileId)
        } catch (_: Exception) {
            null
        }
        if (credential == null) {
            return SessionListResult.Failure(SessionListError.MissingProfile)
        }
        val baseUrl = ProfileBaseUrlValidator.validate(profile.baseUrl).getOrNull()
            ?: return SessionListResult.Failure(SessionListError.InvalidInput)
        return try {
            pageFetcher.fetchPage(
                baseUrl = baseUrl,
                apiKey = credential,
                limit = limit,
                offset = offset
            )
        } catch (_: Exception) {
            SessionListResult.Failure(SessionListError.Network)
        }
    }
}
