package io.github.excludebarrier.orsikrelay.data.run

import io.github.excludebarrier.orsikrelay.data.profile.ServerProfileRepository
import io.github.excludebarrier.orsikrelay.domain.run.RunSteerError
import io.github.excludebarrier.orsikrelay.domain.run.RunSteerRequester
import io.github.excludebarrier.orsikrelay.domain.run.RunSteerResult
import io.github.excludebarrier.orsikrelay.domain.run.RunSteerTransport
import io.github.excludebarrier.orsikrelay.network.HermesRunSteerClient
import io.github.excludebarrier.orsikrelay.security.credential.CredentialStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Production [RunSteerRequester].
 *
 * Loads the profile metadata and the credential separately for the
 * given profile ID and then delegates the single authenticated
 * `POST /v1/runs/{run_id}/steer` write to [RunSteerTransport]
 * (production: [HermesRunSteerClient]).
 *
 * Every failure maps to a closed [RunSteerError] reason: no profile
 * data, URL, key, run ID, steer text or exception text ever escapes
 * into the result. A missing or incomplete profile (metadata,
 * credential or an empty key), a blank profile ID, run ID or steer
 * text, and a store failure all report [RunSteerError.InvalidInput].
 * A transport that throws is reported as [RunSteerError.Network];
 * every closed [RunSteerResult] the transport returns — including
 * [RunSteerResult.NotAccepting] — is preserved as-is. The steer POST
 * is never retried.
 *
 * [steerRun] moves the profile/credential reads and the network write
 * onto [Dispatchers.IO], so it is safe to call from the main thread.
 */
class RunSteerRepository(
    private val profileRepository: ServerProfileRepository,
    private val credentialStore: CredentialStore,
    private val runSteerTransport: RunSteerTransport = HermesRunSteerClient()
) : RunSteerRequester {

    override suspend fun steerRun(
        profileId: String,
        runId: String,
        text: String
    ): RunSteerResult =
        withContext(Dispatchers.IO) {
            if (profileId.isBlank() || runId.isBlank() || text.isBlank()) {
                return@withContext RunSteerResult.Failure(RunSteerError.InvalidInput)
            }
            val profile = try {
                profileRepository.get(profileId)
            } catch (_: Exception) {
                null
            }
            if (profile == null) {
                return@withContext RunSteerResult.Failure(RunSteerError.InvalidInput)
            }
            val credential = try {
                credentialStore.get(profileId)
            } catch (_: Exception) {
                null
            }
            if (credential == null || credential.isEmpty()) {
                return@withContext RunSteerResult.Failure(RunSteerError.InvalidInput)
            }
            try {
                runSteerTransport.steerRun(
                    baseUrl = profile.baseUrl,
                    apiKey = credential,
                    runId = runId,
                    text = text
                )
            } catch (_: Exception) {
                RunSteerResult.Failure(RunSteerError.Network)
            }
        }
}
