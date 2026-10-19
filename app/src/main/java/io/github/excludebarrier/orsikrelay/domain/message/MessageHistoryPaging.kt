package io.github.excludebarrier.orsikrelay.domain.message

/**
 * Result of merging one older server page into the already loaded history.
 *
 * [messages] is the complete visible history in chronological order: the
 * genuinely new older messages followed by [existing]. [hasMore] is
 * `true` only when the server filled the page completely AND at least one
 * message was new. A page that consists only of duplicates therefore
 * stops the paging instead of causing an endless reload loop (for example
 * when a server ignores `offset`).
 */
data class OlderMessageMerge(
    val messages: List<HermesChatMessage>,
    val hasMore: Boolean
)

object MessageHistoryPaging {

    /**
     * Prepends the older [page] to [existing], dropping duplicates by
     * message ID. The server page order is preserved, and IDs are deduped
     * both against the existing history and within the page itself.
     *
     * `hasMore` follows the pagination contract verified against Hermes:
     * the raw returned row count must equal `limit` and the page must have
     * contributed at least one message that was not already loaded.
     */
    fun mergeOlder(
        existing: List<HermesChatMessage>,
        page: MessagePage
    ): OlderMessageMerge {
        val seen = HashSet<Long>(existing.size + page.messages.size)
        existing.forEach { seen += it.id }
        val prepended = ArrayList<HermesChatMessage>(page.messages.size)
        for (message in page.messages) {
            if (seen.add(message.id)) {
                prepended += message
            }
        }
        return OlderMessageMerge(
            messages = prepended + existing,
            hasMore = page.hasMoreOnServer && prepended.isNotEmpty()
        )
    }
}
