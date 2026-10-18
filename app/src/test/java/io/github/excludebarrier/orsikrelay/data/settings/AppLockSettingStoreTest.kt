package io.github.excludebarrier.orsikrelay.data.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class AppLockSettingStoreTest {

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
        val store = PreferenceAppLockSettingStore(
            readStoredValue = { error("preferences unavailable") },
            writeStoredValue = {}
        )

        assertEquals(false, store.read())
    }

    @Test
    fun writePersistsTheBoolean() {
        var stored: String? = null
        val store = PreferenceAppLockSettingStore(
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
        val store = PreferenceAppLockSettingStore(
            readStoredValue = { null },
            writeStoredValue = { error("disk full") }
        )

        store.write(true)
    }

    private fun storeReturning(value: String?) = PreferenceAppLockSettingStore(
        readStoredValue = { value },
        writeStoredValue = {}
    )
}
