package io.github.excludebarrier.orsikrelay.domain.session

/** Closed result of one session-page fetch. */
sealed interface SessionListResult {
    data class Success(val page: SessionPage) : SessionListResult
    data class Failure(val error: SessionListError) : SessionListResult
}
