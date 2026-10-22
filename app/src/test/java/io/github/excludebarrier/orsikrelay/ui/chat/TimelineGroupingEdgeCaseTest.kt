package io.github.excludebarrier.orsikrelay.ui.chat

import io.github.excludebarrier.orsikrelay.domain.message.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * groupTools at scale: a thousand rows must stay linear, keep their order and
 * only ever collapse adjacent TOOL rows.
 */
class TimelineGroupingEdgeCaseTest {

    private fun toolRow(index: Int, name: String? = "tool_${index % 7}", local: Boolean = false) =
        TimelineRow(
            key = "t$index",
            role = MessageRole.TOOL,
            content = "",
            toolName = name,
            localTool = local
        )

    private fun textRow(index: Int, role: MessageRole = MessageRole.ASSISTANT) =
        TimelineRow(key = "m$index", role = role, content = "text $index")

    @Test
    fun aThousandConsecutiveToolsBecomeOneGroup() {
        val rows = (0 until 1000).map { toolRow(it) }

        val items = groupTools(rows)

        assertEquals(1, items.size)
        val group = items.single() as TimelineItem.ToolGroup
        assertEquals("tools:t0", group.key)
        assertEquals(1000, group.rows.size)
        assertEquals((0 until 7).map { "tool_$it" }, group.names)
        assertFalse(group.live)
        assertEquals(rows.map { it.key }, group.rows.map { it.key })
    }

    @Test
    fun aThousandAlternatingToolAndAssistantRowsStayInOrder() {
        val rows = (0 until 1000).map { index ->
            if (index % 2 == 0) toolRow(index) else textRow(index)
        }

        val items = groupTools(rows)

        assertEquals(1000, items.size)
        assertTrue(items.all { it is TimelineItem.Single })
        assertEquals(rows.map { it.key }, items.map { it.key })
    }

    @Test
    fun everyToolSeparatedByAnotherRoleStaysAPlainRow() {
        val roles = listOf(MessageRole.USER, MessageRole.ASSISTANT, MessageRole.SYSTEM, MessageRole.TOOL)
        val rows = (0 until 1000).map { index ->
            val role = roles[index % roles.size]
            if (role == MessageRole.TOOL) toolRow(index) else textRow(index, role)
        }

        val items = groupTools(rows)

        assertEquals(1000, items.size)
        assertTrue(items.all { it is TimelineItem.Single })
        assertEquals(rows.map { it.key }, items.map { it.key })
    }

    @Test
    fun oneOtherRowSplitsAThousandToolsIntoTwoStableGroups() {
        val rows = (0 until 500).map { toolRow(it) } +
            textRow(500, MessageRole.SYSTEM) +
            (500 until 1000).map { toolRow(it) }

        val items = groupTools(rows)

        assertEquals(3, items.size)
        val first = items[0] as TimelineItem.ToolGroup
        val middle = items[1] as TimelineItem.Single
        val second = items[2] as TimelineItem.ToolGroup
        assertEquals("tools:t0", first.key)
        assertEquals(500, first.rows.size)
        assertEquals("m500", middle.key)
        assertEquals("tools:t500", second.key)
        assertEquals(500, second.rows.size)
    }

    @Test
    fun blankNamesAddNothingWhileLiveSurvivesALargeGroup() {
        val names = listOf(null, "", "   ")
        val rows = (0 until 1000).map { toolRow(it, name = names[it % names.size], local = true) }

        val group = groupTools(rows).single() as TimelineItem.ToolGroup

        assertTrue(group.names.isEmpty())
        assertTrue(group.live)
    }

    @Test
    fun duplicateNamesAreDistinctInFirstUseOrder() {
        val names = listOf("a", "b", "a", "c", "b", "d")
        val rows = names.mapIndexed { index, name -> toolRow(index, name = name) }

        val group = groupTools(rows).single() as TimelineItem.ToolGroup

        assertEquals(listOf("a", "b", "c", "d"), group.names)
    }
}
