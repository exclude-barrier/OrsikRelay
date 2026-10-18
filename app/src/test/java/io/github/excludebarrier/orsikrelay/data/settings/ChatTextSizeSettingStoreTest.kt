package io.github.excludebarrier.orsikrelay.data.settings

import io.github.excludebarrier.orsikrelay.domain.settings.ChatTextSize
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatTextSizeSettingStoreTest {

    @Test
    fun readsAPersistedSize() {
        val store = PreferenceChatTextSizeSettingStore(
            readStoredValue = { "Large" },
            writeStoredValue = {}
        )

        assertEquals(ChatTextSize.Large, store.read())
    }

    @Test
    fun absentValueFallsBackToNormal() {
        val store = PreferenceChatTextSizeSettingStore(
            readStoredValue = { null },
            writeStoredValue = {}
        )

        assertEquals(ChatTextSize.Normal, store.read())
    }

    @Test
    fun corruptValueFallsBackToNormal() {
        val store = PreferenceChatTextSizeSettingStore(
            readStoredValue = { "not-a-size" },
            writeStoredValue = {}
        )

        assertEquals(ChatTextSize.Normal, store.read())
    }

    @Test
    fun failedReadFallsBackToNormal() {
        val store = PreferenceChatTextSizeSettingStore(
            readStoredValue = { error("preferences unavailable") },
            writeStoredValue = {}
        )

        assertEquals(ChatTextSize.Normal, store.read())
    }

    @Test
    fun writePersistsTheEnumName() {
        var stored: String? = null
        val store = PreferenceChatTextSizeSettingStore(
            readStoredValue = { stored },
            writeStoredValue = { stored = it }
        )

        store.write(ChatTextSize.Small)

        assertEquals("Small", stored)
    }

    @Test
    fun failedWriteDoesNotThrow() {
        val store = PreferenceChatTextSizeSettingStore(
            readStoredValue = { null },
            writeStoredValue = { error("disk full") }
        )

        store.write(ChatTextSize.Large)
    }
}
