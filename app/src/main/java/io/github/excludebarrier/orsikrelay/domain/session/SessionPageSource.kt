package io.github.excludebarrier.orsikrelay.domain.session

/**
 * Small read seam for the session list (Mission 004A).
 *
 * The UI depends only on this interface and the domain types. The
 * production implementation is the repository in the `data` package,
 * which loads metadata and credential separately and delegates the
 * authenticated read to the network client. Compose tests inject
 * synthetic fakes of this interface.
 *
 * Implementations must be safe to call from `Dispatchers.IO` and must
 * never leak the API key, response bodies or exception text into the
 * result.
 */
fun interface SessionPageSource {
    fun fetchPage(
        profileId: String,
        limit: Int,
        offset: Int
    ): SessionListResult
}
