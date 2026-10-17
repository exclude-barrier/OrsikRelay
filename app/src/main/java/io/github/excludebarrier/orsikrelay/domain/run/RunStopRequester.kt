package io.github.excludebarrier.orsikrelay.domain.run

/**
 * Small UI seam for stopping a running agent run.
 *
 * The UI depends only on this interface and the domain types. The
 * production implementation is the repository in the `data` package,
 * which loads the profile metadata and the credential separately by
 * profile ID and delegates the authenticated POST to the network
 * client. Compose tests inject synthetic fakes of this interface.
 *
 * The method is suspend so the production implementation can move the
 * profile/credential reads and the POST onto `Dispatchers.IO` without
 * blocking the calling (main) thread. Implementations must never retry
 * the POST automatically and must never leak the API key, response
 * bodies or exception text into the result.
 */
interface RunStopRequester {
    suspend fun stopRun(profileId: String, runId: String): RunStopResult
}
