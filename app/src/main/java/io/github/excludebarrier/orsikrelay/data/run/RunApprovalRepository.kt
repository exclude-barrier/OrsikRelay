package io.github.excludebarrier.orsikrelay.data.run

import io.github.excludebarrier.orsikrelay.data.profile.ServerProfileRepository
import io.github.excludebarrier.orsikrelay.domain.run.RunApprovalChoice
import io.github.excludebarrier.orsikrelay.domain.run.RunApprovalError
import io.github.excludebarrier.orsikrelay.domain.run.RunApprovalRequester
import io.github.excludebarrier.orsikrelay.domain.run.RunApprovalResult
import io.github.excludebarrier.orsikrelay.domain.run.RunApprovalTransport
import io.github.excludebarrier.orsikrelay.network.HermesRunApprovalClient
import io.github.excludebarrier.orsikrelay.security.credential.CredentialStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Production [RunApprovalRequester] (Mission 014).
 *
 * Loads the profile metadata and the credential separately for the
 * given profile ID and then delegates the single authenticated
 * `POST /v1/runs/{run_id}/approval` write to [RunApprovalTransport]
 * (production: [HermesRunApprovalClient]).
 *
 * Every failure maps to a closed [RunApprovalError] reason: no profile
 * data, URL, key, run ID, request ID or exception text ever escapes
 * into the result. A missing or incomplete profile (metadata,
 * credential or an empty key), a blank profile/run/request ID, and a
 * store failure all report [RunApprovalError.InvalidInput]. A
 * transport that throws is reported as [RunApprovalError.Network];
 * every closed [RunApprovalResult.Failure] the transport returns is
 * preserved as-is. The POST is never retried.
 *
 * [respond] moves the profile/credential reads and the network write
 * onto [Dispatchers.IO], so it is safe to call from the main thread.
 */
class RunApprovalRepository(
    private val profileRepository: ServerProfileRepository,
    private val credentialStore: CredentialStore,
    private val runApprovalTransport: RunApprovalTransport = HermesRunApprovalClient()
) : RunApprovalRequester {

    override suspend fun respond(
        profileId: String,
        runId: String,
        requestId: String,
        choice: RunApprovalChoice
    ): RunApprovalResult = withContext(Dispatchers.IO) {
        if (profileId.isBlank() || runId.isBlank() || requestId.isBlank()) {
            return@withContext RunApprovalResult.Failure(RunApprovalError.InvalidInput)
        }
        val profile = try {
            profileRepository.get(profileId)
        } catch (_: Exception) {
            null
        }
        if (profile == null) {
            return@withContext RunApprovalResult.Failure(RunApprovalError.InvalidInput)
        }
        val credential = try {
            credentialStore.get(profileId)
        } catch (_: Exception) {
            null
        }
        if (credential == null || credential.isEmpty()) {
            return@withContext RunApprovalResult.Failure(RunApprovalError.InvalidInput)
        }
        try {
            runApprovalTransport.respondApproval(
                baseUrl = profile.baseUrl,
                apiKey = credential,
                runId = runId,
                requestId = requestId,
                choice = choice
            )
        } catch (_: Exception) {
            RunApprovalResult.Failure(RunApprovalError.Network)
        }
    }
}
