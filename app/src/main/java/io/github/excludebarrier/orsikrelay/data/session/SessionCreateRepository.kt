package io.github.excludebarrier.orsikrelay.data.session

import io.github.excludebarrier.orsikrelay.data.profile.ServerProfileRepository
import io.github.excludebarrier.orsikrelay.domain.profile.ProfileBaseUrlValidator
import io.github.excludebarrier.orsikrelay.domain.session.SessionCreateError
import io.github.excludebarrier.orsikrelay.domain.session.SessionCreateFetcher
import io.github.excludebarrier.orsikrelay.domain.session.SessionCreateResult
import io.github.excludebarrier.orsikrelay.domain.session.SessionCreator
import io.github.excludebarrier.orsikrelay.network.HermesSessionCreateClient
import io.github.excludebarrier.orsikrelay.security.credential.CredentialStore

/**
 * Production [SessionCreator] (Mission 005A).
 *
 * Loads the profile metadata and the credential separately for the given
 * profile ID and then delegates the single authenticated POST to
 * [SessionCreateFetcher] (production: [HermesSessionCreateClient]). The
 * optional title is sanitized locally before the request: blank input
 * becomes `null` (sent as an empty body), otherwise it is trimmed,
 * checked for ISO control characters and limited to 512 characters.
 *
 * Every failure maps to a generic [SessionCreateError] reason: no
 * profile data, URL, key, response or exception text ever escapes into
 * the result. The POST is never retried.
 *
 * Expected to run on `Dispatchers.IO` (SharedPreferences, Keystore and
 * network I/O).
 */
class SessionCreateRepository(
    private val profileRepository: ServerProfileRepository,
    private val credentialStore: CredentialStore,
    private val createFetcher: SessionCreateFetcher = HermesSessionCreateClient()
) : SessionCreator {

    override fun createSession(profileId: String, title: String?): SessionCreateResult {
        if (profileId.isBlank()) {
            return SessionCreateResult.Failure(SessionCreateError.MissingProfile)
        }
        val profile = try {
            profileRepository.get(profileId)
        } catch (_: Exception) {
            null
        }
        if (profile == null) {
            return SessionCreateResult.Failure(SessionCreateError.MissingProfile)
        }
        val credential = try {
            credentialStore.get(profileId)
        } catch (_: Exception) {
            null
        }
        if (credential == null || credential.isEmpty()) {
            return SessionCreateResult.Failure(SessionCreateError.MissingProfile)
        }
        val baseUrl = ProfileBaseUrlValidator.validate(profile.baseUrl).getOrNull()
            ?: return SessionCreateResult.Failure(SessionCreateError.InvalidInput)
        val trimmed = title?.trim()?.takeIf { it.isNotEmpty() }
        if (trimmed != null &&
            (trimmed.length > TITLE_MAX_LENGTH || trimmed.any { it.isISOControl() })
        ) {
            return SessionCreateResult.Failure(SessionCreateError.InvalidInput)
        }
        return try {
            createFetcher.createSession(
                baseUrl = baseUrl,
                apiKey = credential,
                title = trimmed
            )
        } catch (_: Exception) {
            SessionCreateResult.Failure(SessionCreateError.Network)
        }
    }

    private companion object {
        const val TITLE_MAX_LENGTH = 512
    }
}
