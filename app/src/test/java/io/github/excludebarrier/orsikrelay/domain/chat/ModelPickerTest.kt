package io.github.excludebarrier.orsikrelay.domain.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelPickerTest {
    private val sonnet = SessionModelOption("anthropic", "claude-sonnet-5-5")
    private val opus = SessionModelOption("anthropic", "claude-opus-5-5")
    private val gpt = SessionModelOption("openai", "gpt-5.5")
    private val flash = SessionModelOption("deepseek", "deepseek-flash")
    private val person = SessionModelOption("openai", "personal-sonnet")
    private val all = listOf(sonnet, opus, gpt, flash, person)

    private fun ids(rows: List<ModelPickerRow>) = rows.items().map { it.option.model }

    @Test fun theFilterIsASubstringOfProviderAndModelIgnoringCase() {
        assertEquals(listOf("gpt-5.5", "personal-sonnet"), ids(modelPickerRows("OPENAI", all, null, emptyList())))
        assertEquals(listOf("deepseek-flash"), ids(modelPickerRows("seek/deep", all, null, emptyList())))
        assertTrue(modelPickerRows("nothing", all, null, emptyList()).isEmpty())
    }

    @Test fun wordStartMatchesComeFirstAndTheMatchIsMarkedInTheName() {
        val rows = modelPickerRows("son", all, null, emptyList())
        // "sonnet" starts a word in "claude-sonnet"; "personal" only contains it.
        assertEquals(listOf("claude-sonnet-5-5", "personal-sonnet", ), ids(rows))
        assertEquals(7..9, rows.items().first().match)
        assertTrue(rows.none { it !is ModelPickerRow.Item })
    }

    @Test fun aProviderOnlyMatchHasNoMarkInTheName() {
        assertNull(modelPickerRows("anthropic", all, null, emptyList()).items().first().match)
    }

    @Test fun withoutAFilterCurrentAndTwoRecentComeFirstThenProviders() {
        val rows = modelPickerRows(
            " ", all, current = gpt.qualified,
            recent = listOf(gpt.qualified, "gone::model", flash.qualified, opus.qualified, sonnet.qualified)
        )
        assertEquals(ModelPickerRow.RecentHeader, rows.first())
        val top = rows.drop(1).take(3).map { (it as ModelPickerRow.Item) }
        assertEquals(listOf(gpt, flash, opus), top.map { it.option })
        assertEquals(listOf(true, false, false), top.map { it.current })
        assertEquals(
            listOf(ModelPickerRow.ProviderHeader("anthropic"), ModelPickerRow.Item(sonnet),
                ModelPickerRow.ProviderHeader("openai"), ModelPickerRow.Item(person)),
            rows.drop(4)
        )
    }

    @Test fun withoutHistoryThereIsNoRecentSection() {
        val rows = modelPickerRows("", listOf(sonnet, gpt), null, emptyList())
        assertEquals(
            listOf(ModelPickerRow.ProviderHeader("anthropic"), ModelPickerRow.Item(sonnet),
                ModelPickerRow.ProviderHeader("openai"), ModelPickerRow.Item(gpt)),
            rows
        )
    }

    @Test fun onlyAModelDraftHasAFilter() {
        assertEquals("son", modelPickerQuery("/model son"))
        assertEquals("", modelPickerQuery("/model "))
        assertNull(modelPickerQuery("/model"))
        assertNull(modelPickerQuery("text /model son"))
    }
}
