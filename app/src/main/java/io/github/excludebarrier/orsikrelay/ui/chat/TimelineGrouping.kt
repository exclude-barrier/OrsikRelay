package io.github.excludebarrier.orsikrelay.ui.chat

import io.github.excludebarrier.orsikrelay.domain.message.MessageRole

/** One entry of the rendered timeline: a single row or a run of tool calls. */
internal sealed interface TimelineItem {
    val key: String

    data class Single(val row: TimelineRow) : TimelineItem {
        override val key: String get() = row.key
    }

    /**
     * Two or more tool rows in a row, shown as one collapsible block.
     * [names] are the distinct, already redacted tool names in order of
     * first use (never content or arguments).
     */
    data class ToolGroup(val rows: List<TimelineRow>) : TimelineItem {
        override val key: String get() = "tools:" + rows.first().key
        val names: List<String> get() = rows.mapNotNull { it.toolName?.takeIf(String::isNotBlank) }.distinct()
        val live: Boolean get() = rows.any { it.localTool }
    }
}

/**
 * Collapses consecutive TOOL rows into one [TimelineItem.ToolGroup] so a
 * turn with many tool calls does not fill the chat with one line per call.
 * A single tool row stays a plain row; every other role is untouched and
 * the order is kept. The group key follows its first row, so it stays
 * stable while a live group grows.
 */
internal fun groupTools(rows: List<TimelineRow>): List<TimelineItem> {
    val items = ArrayList<TimelineItem>(rows.size)
    var run = ArrayList<TimelineRow>()
    fun flush() {
        when (run.size) {
            0 -> Unit
            1 -> items += TimelineItem.Single(run.first())
            else -> items += TimelineItem.ToolGroup(run)
        }
        run = ArrayList()
    }
    for (row in rows) {
        if (row.role == MessageRole.TOOL) {
            run += row
        } else {
            flush()
            items += TimelineItem.Single(row)
        }
    }
    flush()
    return items
}
