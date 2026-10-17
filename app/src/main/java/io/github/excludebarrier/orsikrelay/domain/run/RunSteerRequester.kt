package io.github.excludebarrier.orsikrelay.domain.run

/**
 * Small UI seam for steering a running agent run.
 *
 * The UI depends only on this interface and the domain types. The
 * production implementation is the repository in the `data` package,
 * which loads the profile metadata and the credential separately by
 * profile ID and delegates the authenticated POST to the network
 * client. Compose tests inject synthetic fakes of this interface.
 *
 * A [RunSteerResult.Queued] answer only means queued/accepted: the run
 * stays active. Implementations must never retry the POST automatically
 * and must never leak the API key, steer text, response bodies or
 * exception text into the result.
 */
interface RunSteerRequester {
    suspend fun steerRun(profileId: String, runId: String, text: String): RunSteerResult
}
