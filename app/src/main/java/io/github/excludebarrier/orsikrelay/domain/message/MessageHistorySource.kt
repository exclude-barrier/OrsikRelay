package io.github.excludebarrier.orsikrelay.domain.message

/**
 * Small read-only seam for the chat history.
 *
 * The UI depends only on this interface and the domain types. The
 * production implementation is the repository in the `data` package,
 * which loads profile metadata and credential separately and delegates
 * the authenticated read to the network client. Compose tests inject
 * synthetic fakes of this interface.
 *
 * Implementations must be safe to call from `Dispatchers.IO` and must
 * never leak the API key, response bodies or exception text into the
 * result.
 */
fun interface MessageHistorySource {
    fun loadHistory(profileId: String, sessionId: String): MessageHistoryResult
}
