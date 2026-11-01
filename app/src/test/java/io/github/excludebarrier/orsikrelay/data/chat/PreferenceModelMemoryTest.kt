package io.github.excludebarrier.orsikrelay.data.chat

import io.github.excludebarrier.orsikrelay.domain.chat.MODEL_MEMORY_RECENT
import io.github.excludebarrier.orsikrelay.domain.chat.MODEL_MEMORY_SESSIONS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreferenceModelMemoryTest {
    private val stored = mutableMapOf<String, String>()
    private val memory = PreferenceModelMemory(read = { stored[it] }, write = { stored.putAll(it) })

    @Test fun rememberSetsTheChatsModelAndPutsItFirstInTheRecentList() {
        memory.remember("p1", "s1", "a::one")
        memory.remember("p1", "s2", "b::two")
        memory.remember("p1", "s1", "c::three")

        assertEquals("c::three", memory.current("p1", "s1"))
        assertEquals("b::two", memory.current("p1", "s2"))
        assertEquals(listOf("c::three", "b::two", "a::one"), memory.recent("p1"))
    }

    @Test fun serversAreKeptApart() {
        memory.remember("p1", "s1", "a::one")
        assertNull(memory.current("p2", "s1"))
        assertTrue(memory.recent("p2").isEmpty())
    }

    @Test fun bothListsAreBounded() {
        repeat(MODEL_MEMORY_SESSIONS + 5) { memory.remember("p1", "s$it", "a::m$it") }
        assertEquals(MODEL_MEMORY_RECENT, memory.recent("p1").size)
        assertNull(memory.current("p1", "s0"))
        assertEquals("a::m${MODEL_MEMORY_SESSIONS + 4}", memory.current("p1", "s${MODEL_MEMORY_SESSIONS + 4}"))
    }

    @Test fun invalidValuesAreNeitherStoredNorRead() {
        memory.remember("p1", "s1", "no-separator")
        memory.remember("p1", "s\tx", "a::one")
        assertTrue(stored.isEmpty())
        stored["recent_v1:p1"] = "bad value\na::one"
        assertEquals(listOf("a::one"), memory.recent("p1"))
    }
}
