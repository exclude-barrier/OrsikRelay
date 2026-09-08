package io.github.excludebarrier.orsikrelay.data.run

import io.github.excludebarrier.orsikrelay.data.profile.ServerProfileRepository
import io.github.excludebarrier.orsikrelay.domain.run.RunStopError
import io.github.excludebarrier.orsikrelay.domain.run.RunStopRequester
import io.github.excludebarrier.orsikrelay.domain.run.RunStopResult
import io.github.excludebarrier.orsikrelay.domain.run.RunStopTransport
import io.github.excludebarrier.orsikrelay.network.HermesRunStopClient
import io.github.excludebarrier.orsikrelay.security.credential.CredentialStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Production [RunStopRequester] (Mission 007A).
 *
 * Loads the profile metadata and the credential separately for the
 * given profile ID and then delegates the single authenticated
 * `POST /v1/runs/{run_id}/stop` write to [RunStopTransport]
 * (production: [HermesRunStopClient]).
 *
 * Every failure maps to a closed [RunStopError] reason: no profile
 * data, URL, key, run ID or exception text ever escapes into the
 * result. A missing or incomplete profile (metadata, credential or an
 * empty key), a blank profile/run ID, and a store failure all report
 * [RunStopError.InvalidInput]. A transport that throws is reported as
 * [RunStopError.Network]; every closed [RunStopResult.Failure] the
 * transport returns is preserved as-is. The POST is never retried.
 *
 * [stopRun] moves the profile/credential reads and the network write
 * onto [Dispatchers.IO], so it is safe to call from the main thread.
 */
class RunStopRepository(
    private val profileRepository: ServerProfileRepository,
    private val credentialStore: CredentialStore,
    private val runStopTransport: RunStopTransport = HermesRunStopClient()
) : RunStopRequester {

    override suspend fun stopRun(profileId: String, runId: String): RunStopResult =
        withContext(Dispatchers.IO) {
            if (profileId.isBlank() || runId.isBlank()) {
                return@withContext RunStopResult.Failure(RunStopError.InvalidInput)
            }
            val profile = try {
                profileRepository.get(profileId)
            } catch (_: Exception) {
                null
            }
            if (profile == null) {
                return@withContext RunStopResult.Failure(RunStopError.InvalidInput)
            }
            val credential = try {
                credentialStore.get(profileId)
            } catch (_: Exception) {
                null
            }
            if (credential == null || credential.isEmpty()) {
                return@withContext RunStopResult.Failure(RunStopError.InvalidInput)
            }
            try {
                runStopTransport.stopRun(
                    baseUrl = profile.baseUrl,
                    apiKey = credential,
                    runId = runId
                )
            } catch (_: Exception) {
                RunStopResult.Failure(RunStopError.Network)
            }
        }
}
