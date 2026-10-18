package io.github.excludebarrier.orsikrelay.data.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenProtectionSettingStoreTest {

    @Test
    fun readsEnabledAndDisabledValues() {
        assertEquals(true, storeReturning("true").read())
        assertEquals(false, storeReturning("false").read())
    }

    @Test
    fun absentValueDefaultsToOff() {
        assertEquals(false, storeReturning(null).read())
    }

    @Test
    fun corruptValueDefaultsToOff() {
        assertEquals(false, storeReturning("maybe").read())
    }

    @Test
    fun failedReadDefaultsToOff() {
        val store = PreferenceScreenProtectionSettingStore(
            readStoredValue = { error("preferences unavailable") },
            writeStoredValue = {}
        )

        assertEquals(false, store.read())
    }

    @Test
    fun writePersistsTheBoolean() {
        var stored: String? = null
        val store = PreferenceScreenProtectionSettingStore(
            readStoredValue = { stored },
            writeStoredValue = { stored = it }
        )

        store.write(true)
        assertEquals("true", stored)

        store.write(false)
        assertEquals("false", stored)
    }

    @Test
    fun failedWriteDoesNotThrow() {
        val store = PreferenceScreenProtectionSettingStore(
            readStoredValue = { null },
            writeStoredValue = { error("disk full") }
        )

        store.write(true)
    }

    private fun storeReturning(value: String?) = PreferenceScreenProtectionSettingStore(
        readStoredValue = { value },
        writeStoredValue = {}
    )
}
