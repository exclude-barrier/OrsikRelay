package io.github.excludebarrier.orsikrelay.domain.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageHistoryPagingTest {

    @Test
    fun olderPageIsPrependedInServerOrder() {
        val existing = listOf(message(3), message(4))

        val merged = MessageHistoryPaging.mergeOlder(
            existing = existing,
            page = page(messages = listOf(message(1), message(2)), returned = 2, limit = 2)
        )

        assertEquals(listOf(1L, 2L, 3L, 4L), merged.messages.map { it.id })
        assertTrue(merged.hasMore)
    }

    @Test
    fun duplicateIdsAreDiscardedOnceAndKeepTheNewContentOrder() {
        val existing = listOf(message(3), message(4))

        val merged = MessageHistoryPaging.mergeOlder(
            existing = existing,
            page = page(
                messages = listOf(message(1), message(3), message(2), message(2)),
                returned = 4,
                limit = 4
            )
        )

        assertEquals(listOf(1L, 2L, 3L, 4L), merged.messages.map { it.id })
        assertTrue(merged.hasMore)
    }

    @Test
    fun duplicateOnlyPageStopsPaging() {
        val existing = listOf(message(3), message(4))

        val merged = MessageHistoryPaging.mergeOlder(
            existing = existing,
            page = page(messages = listOf(message(3), message(4)), returned = 2, limit = 2)
        )

        assertEquals(listOf(3L, 4L), merged.messages.map { it.id })
        assertFalse(merged.hasMore)
    }

    @Test
    fun shortPageStopsPaging() {
        val merged = MessageHistoryPaging.mergeOlder(
            existing = listOf(message(5)),
            page = page(messages = listOf(message(4)), returned = 1, limit = 200)
        )

        assertEquals(listOf(4L, 5L), merged.messages.map { it.id })
        assertFalse(merged.hasMore)
    }

    @Test
    fun fullPageWithoutVisibleNewMessagesStopsPaging() {
        val merged = MessageHistoryPaging.mergeOlder(
            existing = listOf(message(5)),
            page = page(messages = emptyList(), returned = 200, limit = 200)
        )

        assertEquals(listOf(5L), merged.messages.map { it.id })
        assertFalse(merged.hasMore)
    }

    @Test
    fun pageWithoutPaginationMetadataNeverClaimsMore() {
        val page = page(messages = listOf(message(1)), returned = 1, limit = 0)

        assertFalse(page.hasMoreOnServer)
    }

    private fun page(
        messages: List<HermesChatMessage>,
        returned: Int,
        limit: Int
    ) = MessagePage(
        messages = messages,
        limit = limit,
        returned = returned
    )

    private fun message(id: Long) = HermesChatMessage(
        id = id,
        role = MessageRole.USER,
        content = "Nachricht $id",
        timestampSeconds = null,
        toolName = null
    )
}
