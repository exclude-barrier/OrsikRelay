package io.github.excludebarrier.orsikrelay.data.run

import io.github.excludebarrier.orsikrelay.data.profile.ServerProfileRepository
import io.github.excludebarrier.orsikrelay.domain.run.LostRun
import io.github.excludebarrier.orsikrelay.domain.run.RunResolveError
import io.github.excludebarrier.orsikrelay.domain.run.RunResolveResult
import io.github.excludebarrier.orsikrelay.domain.run.RunResolveTransport
import io.github.excludebarrier.orsikrelay.domain.run.RunUnknownResolver
import io.github.excludebarrier.orsikrelay.network.HermesRunResolveClient
import io.github.excludebarrier.orsikrelay.security.credential.CredentialStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Production [RunUnknownResolver]: loads the profile and its credential
 * separately and hands the single POST to [transport] on `Dispatchers.IO`.
 * A missing profile or credential fails closed; nothing is retried.
 */
class RunUnknownResolveRepository(
    private val profileRepository: ServerProfileRepository,
    private val credentialStore: CredentialStore,
    private val transport: RunResolveTransport = HermesRunResolveClient()
) : RunUnknownResolver {

    override suspend fun resolve(profileId: String, runId: String, lostRun: LostRun): RunResolveResult =
        withContext(Dispatchers.IO) {
            val failed = RunResolveResult.Failure(RunResolveError.Failed)
            if (profileId.isBlank() || runId.isBlank()) return@withContext failed
            val profile = try {
                profileRepository.get(profileId)
            } catch (_: Exception) {
                null
            } ?: return@withContext failed
            val credential = try {
                credentialStore.get(profileId)
            } catch (_: Exception) {
                null
            }
            if (credential == null || credential.isEmpty()) return@withContext failed
            try {
                transport.resolve(profile.baseUrl, credential, runId, lostRun)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                failed
            }
        }
}
