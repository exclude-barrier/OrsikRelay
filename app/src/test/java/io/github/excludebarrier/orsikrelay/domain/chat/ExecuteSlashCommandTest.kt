package io.github.excludebarrier.orsikrelay.domain.chat

import org.junit.Assert.*
import org.junit.Test

class ExecuteSlashCommandTest {
    @Test fun titleIsPassedExactlyAndInvalidArgumentsNeverInvokeTransport() {
        val calls = mutableListOf<Pair<SlashCommand, String>>()
        val execute = ExecuteSlashCommand { _, _, command, argument ->
            calls += command to argument
            SlashCommandResult.Done
        }
        assertEquals(SlashCommandResult.Done, execute("p", "s", SlashInput.parse("/title New name")!!))
        for (text in listOf("/title", "/title " + "x".repeat(513), "/title a\nb", "/new argument", "/unknown")) {
            assertEquals(SlashCommandResult.Failed, execute("p", "s", SlashInput.parse(text)!!))
        }
        assertEquals(listOf(SlashCommand.TITLE to "New name"), calls)
    }
}
