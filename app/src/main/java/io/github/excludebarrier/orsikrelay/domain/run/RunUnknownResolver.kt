package io.github.excludebarrier.orsikrelay.domain.run

import io.github.excludebarrier.orsikrelay.domain.profile.SecretValue

/**
 * UI seam for closing a run the core lost ([RunServerStatus.UNKNOWN]):
 * `POST /v1/runs/{run_id}/resolve-unknown`. The core then ends the lost
 * turn as interrupted and frees the chat; it never re-runs it. Called
 * only on an explicit user tap and never retried automatically.
 */
interface RunUnknownResolver {
    suspend fun resolve(profileId: String, runId: String, lostRun: LostRun): RunResolveResult
}

/** Authenticated transport behind [RunUnknownResolver]. */
fun interface RunResolveTransport {
    fun resolve(baseUrl: String, apiKey: SecretValue, runId: String, lostRun: LostRun): RunResolveResult
}

sealed interface RunResolveResult {
    data object Resolved : RunResolveResult
    data class Failure(val error: RunResolveError) : RunResolveResult
}

enum class RunResolveError {
    /** The run moved on since the status read (core `stale_generation`); read the status again. */
    Stale,

    /** Anything else (network, TLS, auth, unexpected answer). */
    Failed
}

/** Zero-value [RunUnknownResolver] where nothing is wired (previews, tests). */
object NoOpRunUnknownResolver : RunUnknownResolver {
    override suspend fun resolve(profileId: String, runId: String, lostRun: LostRun): RunResolveResult =
        RunResolveResult.Failure(RunResolveError.Failed)
}
