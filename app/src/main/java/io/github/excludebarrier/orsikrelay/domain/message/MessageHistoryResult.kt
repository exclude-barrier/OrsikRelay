package io.github.excludebarrier.orsikrelay.domain.message

/** Closed result of one chat-history load. */
sealed interface MessageHistoryResult {
    data class Success(val page: MessagePage) : MessageHistoryResult
    data class Failure(val error: MessageHistoryError) : MessageHistoryResult
}
