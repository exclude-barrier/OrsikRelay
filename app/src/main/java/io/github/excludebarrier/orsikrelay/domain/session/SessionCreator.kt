package io.github.excludebarrier.orsikrelay.domain.session

/**
 * Small UI seam for creating a new session.
 *
 * The UI depends only on this interface and the domain types. The
 * production implementation is the repository in the `data` package,
 * which loads the profile metadata and the credential separately and
 * delegates the authenticated POST to the network client. Compose tests
 * inject synthetic fakes of this interface.
 *
 * Implementations must be safe to call from `Dispatchers.IO`, must never
 * retry the POST automatically and must never leak the API key, response
 * bodies or exception text into the result.
 */
fun interface SessionCreator {
    fun createSession(profileId: String, title: String?): SessionCreateResult
}
