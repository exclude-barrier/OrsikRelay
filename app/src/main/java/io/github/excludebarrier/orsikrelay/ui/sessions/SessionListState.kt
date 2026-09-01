package io.github.excludebarrier.orsikrelay.ui.sessions

import io.github.excludebarrier.orsikrelay.domain.session.HermesSession
import io.github.excludebarrier.orsikrelay.domain.session.SessionListError

/**
 * UI state of the session list (Mission 004A).
 *
 * Contains only domain metadata: no API key, no raw response or
 * exception text.
 */
sealed interface SessionListState {
    data object Loading : SessionListState

    data class Ready(
        val sessions: List<HermesSession>,
        val hasMore: Boolean
    ) : SessionListState

    data class Failed(val error: SessionListError) : SessionListState
}
