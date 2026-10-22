package io.github.excludebarrier.orsikrelay.ui.chat

import io.github.excludebarrier.orsikrelay.domain.message.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineGroupingTest {

    private fun row(key: String, role: MessageRole, tool: String? = null, local: Boolean = false) =
        TimelineRow(key = key, role = role, content = if (role == MessageRole.TOOL) "" else "text $key", toolName = tool, localTool = local)

    @Test
    fun consecutiveToolsBecomeOneGroupBetweenTexts() {
        val items = groupTools(
            listOf(
                row("1", MessageRole.USER),
                row("2", MessageRole.TOOL, "terminal"),
                row("3", MessageRole.TOOL, "read_file"),
                row("4", MessageRole.TOOL, "terminal"),
                row("5", MessageRole.ASSISTANT)
            )
        )

        assertEquals(listOf("1", "tools:2", "5"), items.map { it.key })
        val group = items[1] as TimelineItem.ToolGroup
        assertEquals(3, group.rows.size)
        assertEquals(listOf("terminal", "read_file"), group.names)
        assertFalse(group.live)
    }

    @Test
    fun aSingleToolStaysAPlainRow() {
        val items = groupTools(listOf(row("1", MessageRole.USER), row("2", MessageRole.TOOL, "terminal"), row("3", MessageRole.ASSISTANT)))

        assertEquals(listOf("1", "2", "3"), items.map { it.key })
        assertTrue(items.all { it is TimelineItem.Single })
    }

    @Test
    fun toolsSeparatedByTextFormSeparateGroupsAndOrderIsKept() {
        val items = groupTools(
            listOf(
                row("1", MessageRole.TOOL, "a"),
                row("2", MessageRole.TOOL, "b"),
                row("3", MessageRole.ASSISTANT),
                row("4", MessageRole.TOOL, "c"),
                row("5", MessageRole.TOOL, "d")
            )
        )

        assertEquals(listOf("tools:1", "3", "tools:4"), items.map { it.key })
    }

    @Test
    fun aGrowingLiveGroupKeepsItsKeyAndIsLive() {
        val two = listOf(row("l1", MessageRole.TOOL, "terminal", local = true), row("l2", MessageRole.TOOL, "browser", local = true))
        val three = two + row("l3", MessageRole.TOOL, null, local = true)

        val before = groupTools(two).single() as TimelineItem.ToolGroup
        val after = groupTools(three).single() as TimelineItem.ToolGroup
        assertEquals(before.key, after.key)
        assertTrue(after.live)
        // An unnamed live call adds no name.
        assertEquals(listOf("terminal", "browser"), after.names)
    }

    @Test
    fun anEmptyTimelineStaysEmpty() {
        assertTrue(groupTools(emptyList()).isEmpty())
    }
}
