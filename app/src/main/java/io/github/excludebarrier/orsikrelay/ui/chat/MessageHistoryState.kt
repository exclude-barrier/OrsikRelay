package io.github.excludebarrier.orsikrelay.ui.chat

import io.github.excludebarrier.orsikrelay.domain.message.HermesChatMessage
import io.github.excludebarrier.orsikrelay.domain.message.MessageHistoryError

sealed interface MessageHistoryState {
    data object Loading : MessageHistoryState
    data class Ready(val messages: List<HermesChatMessage>) : MessageHistoryState
    data class Failed(val error: MessageHistoryError) : MessageHistoryState
}
