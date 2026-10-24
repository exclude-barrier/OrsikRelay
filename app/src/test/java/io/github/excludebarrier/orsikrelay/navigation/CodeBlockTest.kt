package io.github.excludebarrier.orsikrelay.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class CodeBlockTest {
    @Test
    fun theFenceIsLongerThanAnyBacktickRunInside() {
        assertEquals("```\nls -la\n```", codeBlock("ls -la\n"))
        assertEquals("````\na ``` b\n````", codeBlock("a ``` b"))
        assertEquals("``````\n`````\n``````", codeBlock("`````"))
    }
}
