package io.github.excludebarrier.orsikrelay.domain.session

/**
 * One validated page of the session list.
 *
 * [sessions] preserves the exact server order.
 */
data class SessionPage(
    val sessions: List<HermesSession>,
    val hasMore: Boolean
)
