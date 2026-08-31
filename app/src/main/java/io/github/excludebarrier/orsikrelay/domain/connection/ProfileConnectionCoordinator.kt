package io.github.excludebarrier.orsikrelay.domain.connection

import io.github.excludebarrier.orsikrelay.data.profile.VerifiedProfileSaver
import io.github.excludebarrier.orsikrelay.domain.profile.ProfileDraft

class ProfileConnectionCoordinator(
    private val connectionProbe: ConnectionProbe,
    private val profileSaver: VerifiedProfileSaver
) {
    fun connect(draft: ProfileDraft): ProfileConnectionOutcome {
        return when (val probeResult = connectionProbe.probe(draft)) {
            is HermesConnectionResult.Failure ->
                ProfileConnectionOutcome.Failure(probeResult.reason)

            is HermesConnectionResult.Success -> try {
                val profile = profileSaver.saveVerified(draft)
                ProfileConnectionOutcome.Success(
                    version = probeResult.version,
                    profileId = profile.id
                )
            } catch (_: Exception) {
                ProfileConnectionOutcome.PersistenceFailure
            }
        }
    }
}
