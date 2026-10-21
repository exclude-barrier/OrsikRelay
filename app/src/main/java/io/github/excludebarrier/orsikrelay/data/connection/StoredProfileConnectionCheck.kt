package io.github.excludebarrier.orsikrelay.data.connection

import io.github.excludebarrier.orsikrelay.data.profile.ServerProfileRepository
import io.github.excludebarrier.orsikrelay.domain.connection.ConnectionFailureReason
import io.github.excludebarrier.orsikrelay.domain.connection.ConnectionProbe
import io.github.excludebarrier.orsikrelay.domain.connection.HermesConnectionResult
import io.github.excludebarrier.orsikrelay.domain.home.ServerConnectionCheck
import io.github.excludebarrier.orsikrelay.domain.profile.ProfileDraft
import io.github.excludebarrier.orsikrelay.security.credential.CredentialStore

/**
 * Production [ServerConnectionCheck]: loads the profile metadata and the
 * credential separately and runs the existing [ConnectionProbe]. A missing
 * profile or credential is an invalid input; no profile data, key or
 * exception text escapes into the result.
 */
class StoredProfileConnectionCheck(
    private val profileRepository: ServerProfileRepository,
    private val credentialStore: CredentialStore,
    private val probe: ConnectionProbe
) : ServerConnectionCheck {

    override fun check(profileId: String): HermesConnectionResult {
        val invalid = HermesConnectionResult.Failure(ConnectionFailureReason.INVALID_INPUT)
        if (profileId.isBlank()) return invalid
        val profile = runCatching { profileRepository.get(profileId) }.getOrNull() ?: return invalid
        val credential = runCatching { credentialStore.get(profileId) }.getOrNull() ?: return invalid
        return try {
            probe.probe(ProfileDraft(profile.profileName, profile.baseUrl, credential))
        } catch (_: Exception) {
            HermesConnectionResult.Failure(ConnectionFailureReason.NETWORK)
        }
    }
}
