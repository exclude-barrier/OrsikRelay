package io.github.excludebarrier.orsikrelay.domain.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatTextSizeTest {

    @Test
    fun scalesAreNineTenAndFifteenPercentAboveNormal() {
        assertEquals(0.9f, ChatTextSize.Small.scale, 0.0001f)
        assertEquals(1.0f, ChatTextSize.Normal.scale, 0.0001f)
        assertEquals(1.15f, ChatTextSize.Large.scale, 0.0001f)
    }

    @Test
    fun defaultIsNormal() {
        assertEquals(ChatTextSize.Normal, ChatTextSize.DEFAULT)
    }

    @Test
    fun storedValueResolvesCaseInsensitively() {
        assertEquals(ChatTextSize.Small, ChatTextSize.fromStored("Small"))
        assertEquals(ChatTextSize.Large, ChatTextSize.fromStored("large"))
    }

    @Test
    fun absentOrUnknownValueFallsBackToDefault() {
        assertEquals(ChatTextSize.DEFAULT, ChatTextSize.fromStored(null))
        assertEquals(ChatTextSize.DEFAULT, ChatTextSize.fromStored(""))
        assertEquals(ChatTextSize.DEFAULT, ChatTextSize.fromStored("gigantic"))
    }
}
