package io.github.excludebarrier.orsikrelay.domain.run

/**
 * UI seam for reading the server-side status of a run.
 *
 * The UI depends only on this interface and the domain types. The
 * production implementation is the repository in the `data` package,
 * which loads the profile metadata and the credential separately by
 * profile ID and delegates the authenticated `GET /v1/runs/{run_id}` to
 * the network client. Compose tests inject synthetic fakes.
 *
 * The method is `suspend` so the production implementation can move the
 * profile/credential reads and the GET onto `Dispatchers.IO` without
 * blocking the calling (main) thread. Implementations must never retry
 * automatically and must never leak the API key, response bodies, run
 * IDs or exception text into the result.
 */
interface RunStatusRequester {
    suspend fun getRunStatus(profileId: String, runId: String): RunStatusResult
}

/**
 * Zero-value [RunStatusRequester] used where no run-status read is wired
 * (previews, tests). It reports a closed failure and performs no I/O.
 */
object NoOpRunStatusRequester : RunStatusRequester {
    override suspend fun getRunStatus(profileId: String, runId: String): RunStatusResult =
        RunStatusResult.Failure(RunStatusError.InvalidInput)
}
