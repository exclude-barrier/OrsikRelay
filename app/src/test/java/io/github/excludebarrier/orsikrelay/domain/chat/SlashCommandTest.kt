package io.github.excludebarrier.orsikrelay.domain.chat

import org.junit.Assert.*
import org.junit.Test

class SlashCommandTest {
    @Test fun onlyLeadingSlashIsACommand() {
        assertNull(SlashInput.parse("text /stop"))
        assertNull(SlashInput.parse(" /stop"))
        assertEquals("stop", SlashInput.parse("/stop")?.name)
        assertEquals("A new title", SlashInput.parse("/title A new title")?.argument)
        assertEquals("", SlashInput.parse("/")?.name)
    }

    @Test fun aPathIsOrdinaryTextNotACommand() {
        assertNull(SlashInput.parse("/var/log/syslog, what is in there?"))
        assertNull(SlashInput.parse("/etc/hosts"))
        assertNull(SlashInput.parse("/home/user/notes.txt"))
        assertNull(SlashInput.parse("/notes.txt please"))
        // A plain word stays a command, also an unknown one (clear error, no LLM call).
        assertEquals("compress", SlashInput.parse("/compress")?.name)
        assertNull(SlashInput.parse("/compress")?.command)
    }

    @Test fun filterIsClosedAndDoesNotOfferSkillsOrRunOnSelection() {
        val available = SlashCommand.entries.toSet()
        assertEquals(listOf(SlashCommand.STOP, SlashCommand.STEER), slashSuggestions("/st", available))
        assertTrue(slashSuggestions("sentence /", available).isEmpty())
        assertTrue(slashSuggestions("/title argument", available).isEmpty())
        assertTrue(slashSuggestions("/skills", available).isEmpty())
        assertEquals("/title ", SlashCommand.TITLE.insertion)
        assertEquals(listOf(SlashCommand.NEW), slashSuggestions("/", setOf(SlashCommand.NEW)))
    }

    @Test fun modelSuggestionsKeepProviderIdentityAndNeverBecomeAnExecution() {
        val options = listOf(SessionModelOption("one", "org/model"), SessionModelOption("two", "other"))
        assertEquals(options, modelSuggestions("/model ", options))
        assertEquals(listOf(options.first()), modelSuggestions("/model org/", options))
        assertTrue(modelSuggestions("/model one::org/model ", options).isEmpty())
        assertTrue(modelSuggestions("text /model ", options).isEmpty())
        assertFalse(SessionModelOption("bad provider", "model").isValid())
    }
}
