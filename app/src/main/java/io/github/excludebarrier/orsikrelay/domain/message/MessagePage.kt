package io.github.excludebarrier.orsikrelay.domain.message

/**
 * One validated page of visible chat messages (Mission 004B).
 *
 * [messages] preserves the exact server order of the visible entries.
 * Messages with `display_kind = "hidden"` are already dropped by the
 * network layer and never appear here.
 */
data class MessagePage(
    val messages: List<HermesChatMessage>
)
