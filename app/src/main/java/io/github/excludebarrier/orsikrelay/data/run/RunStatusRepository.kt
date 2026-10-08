package io.github.excludebarrier.orsikrelay.data.run

import io.github.excludebarrier.orsikrelay.data.profile.ServerProfileRepository
import io.github.excludebarrier.orsikrelay.domain.run.RunStatusError
import io.github.excludebarrier.orsikrelay.domain.run.RunStatusRequester
import io.github.excludebarrier.orsikrelay.domain.run.RunStatusResult
import io.github.excludebarrier.orsikrelay.domain.run.RunStatusTransport
import io.github.excludebarrier.orsikrelay.network.HermesRunStatusClient
import io.github.excludebarrier.orsikrelay.security.credential.CredentialStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Production [RunStatusRequester] (P7).
 *
 * Loads the profile metadata and the credential separately for the
 * given profile ID and then delegates the single authenticated
 * `GET /v1/runs/{run_id}` read to [RunStatusTransport] (production:
 * [HermesRunStatusClient]).
 *
 * Every failure maps to a closed [RunStatusError] reason: no profile
 * data, URL, key, run ID or exception text ever escapes into the
 * result. A missing or incomplete profile (metadata, credential or an
 * empty key), a blank profile/run ID, and a store failure all report
 * [RunStatusError.InvalidInput]. A transport that throws is reported as
 * [RunStatusError.Network]; every closed [RunStatusResult.Failure] the
 * transport returns is preserved as-is. The GET is never retried.
 *
 * [getRunStatus] moves the profile/credential reads and the network
 * read onto [Dispatchers.IO], so it is safe to call from the main
 * thread.
 */
class RunStatusRepository(
    private val profileRepository: ServerProfileRepository,
    private val credentialStore: CredentialStore,
    private val runStatusTransport: RunStatusTransport = HermesRunStatusClient()
) : RunStatusRequester {

    override suspend fun getRunStatus(profileId: String, runId: String): RunStatusResult =
        withContext(Dispatchers.IO) {
            if (profileId.isBlank() || runId.isBlank()) {
                return@withContext RunStatusResult.Failure(RunStatusError.InvalidInput)
            }
            val profile = try {
                profileRepository.get(profileId)
            } catch (_: Exception) {
                null
            }
            if (profile == null) {
                return@withContext RunStatusResult.Failure(RunStatusError.InvalidInput)
            }
            val credential = try {
                credentialStore.get(profileId)
            } catch (_: Exception) {
                null
            }
            if (credential == null || credential.isEmpty()) {
                return@withContext RunStatusResult.Failure(RunStatusError.InvalidInput)
            }
            try {
                runStatusTransport.getRunStatus(
                    baseUrl = profile.baseUrl,
                    apiKey = credential,
                    runId = runId
                )
            } catch (error: CancellationException) {
                // A cancelled reconciliation must propagate, not become a
                // closed transport failure.
                throw error
            } catch (_: Exception) {
                RunStatusResult.Failure(RunStatusError.Network)
            }
        }
}
