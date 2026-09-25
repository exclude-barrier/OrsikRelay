package io.github.excludebarrier.orsikrelay.ui.chat

import io.github.excludebarrier.orsikrelay.domain.message.HermesChatMessage
import io.github.excludebarrier.orsikrelay.domain.message.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * History tool redaction (client policy): the timeline is the single merge
 * point of server history and local lines. A TOOL row's `content` is
 * untrusted server data (commands, paths, URLs, tool output, exit codes,
 * approval payloads) and must NEVER reach the UI as display text; only the
 * validated `toolName` metadata is carried, so the screen can render the
 * safe role label (fail closed). Non-tool rows keep their content verbatim.
 */
class ChatUiStateTest {

    @Test
    fun historyToolContentWithSecretJsonIsNeverCarriedIntoTheTimeline() {
        val secret = """{"output":"SECRET_TEST_123","exit_code":0,"error":null}"""
        val row = buildTimeline(
            listOf(toolMessage(1, secret, "terminal")),
            emptyList()
        ).single()

        assertEquals(MessageRole.TOOL, row.role)
        assertEquals("", row.content)
        assertEquals("terminal", row.toolName)
    }

    @Test
    fun historyToolContentWithCommandIsNeverCarriedIntoTheTimeline() {
        val command = """rm -rf /tmp/orsik_p3_e2e_dir && curl http://internal.example/x"""
        val row = buildTimeline(
            listOf(toolMessage(1, command, null)),
            emptyList()
        ).single()

        assertEquals("", row.content)
        assertTrue(command !in row.content)
        assertEquals(null, row.toolName)
    }

    @Test
    fun historyToolContentWithPathAndUrlIsNeverCarriedIntoTheTimeline() {
        val row = buildTimeline(
            listOf(toolMessage(1, "/home/user/.ssh/id_rsa -> https://example.com/a?token=abc", null)),
            emptyList()
        ).single()

        assertEquals("", row.content)
    }

    @Test
    fun historyToolNameMetadataIsPreservedForTheSafeRoleLabel() {
        val row = buildTimeline(
            listOf(toolMessage(7, "ignored", "web_search")),
            emptyList()
        ).single()

        assertEquals("server:7", row.key)
        assertEquals("web_search", row.toolName)
    }

    @Test
    fun nonToolRolesKeepTheirContentVerbatim() {
        val rows = buildTimeline(
            listOf(
                HermesChatMessage(1, MessageRole.USER, "Du-Frage", null, null),
                HermesChatMessage(2, MessageRole.ASSISTANT, "Antwort **markdown**", null, null),
                HermesChatMessage(3, MessageRole.SYSTEM, "System-Hinweis", null, null)
            ),
            emptyList()
        )

        assertEquals("Du-Frage", rows[0].content)
        assertEquals("Antwort **markdown**", rows[1].content)
        assertEquals("System-Hinweis", rows[2].content)
    }

    @Test
    fun localLinesAreAppendedUnchangedAndOrderedAfterHistory() {
        val local = TimelineRow(
            key = "local:1",
            role = MessageRole.TOOL,
            content = "Tool aktiv: terminal",
            toolName = null,
            localTool = true
        )
        val rows = buildTimeline(
            listOf(toolMessage(1, "ignored", "terminal")),
            listOf(local)
        )

        assertEquals(2, rows.size)
        assertEquals("Tool aktiv: terminal", rows[1].content)
        assertTrue(rows[1].localTool)
    }

    private fun toolMessage(id: Long, content: String, toolName: String?) =
        HermesChatMessage(
            id = id,
            role = MessageRole.TOOL,
            content = content,
            timestampSeconds = null,
            toolName = toolName
        )
}
