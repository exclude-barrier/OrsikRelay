package io.github.excludebarrier.orsikrelay.domain.message

/**
 * One validated page of visible chat messages.
 *
 * [messages] preserves the exact server order of the visible entries.
 * Messages with `display_kind = "hidden"` are already dropped by the
 * network layer and never appear here.
 *
 * [limit] and [returned] are the server's pagination numbers.
 * [returned] counts the raw server rows of this page BEFORE hidden entries
 * were dropped, so it - not [messages] size - is the amount by which the
 * offset for the next older page advances. The defaults describe a page
 * without pagination metadata (test fakes): [limit] is `0`, which means
 * "no older page known".
 */
data class MessagePage(
    val messages: List<HermesChatMessage>,
    val limit: Int = 0,
    val returned: Int = messages.size
) {
    /**
     * Whether the server filled this page completely, so another older
     * page may exist. The next load must still prove it by yielding at
     * least one new message.
     */
    val hasMoreOnServer: Boolean
        get() = limit > 0 && returned == limit
}
