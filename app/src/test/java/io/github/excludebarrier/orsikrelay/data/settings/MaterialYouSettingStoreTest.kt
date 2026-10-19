package io.github.excludebarrier.orsikrelay.data.settings

import io.github.excludebarrier.orsikrelay.ui.settings.isMaterialYouAvailable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MaterialYouSettingStoreTest {

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
        val store = PreferenceMaterialYouSettingStore(
            readStoredValue = { error("preferences unavailable") },
            writeStoredValue = {}
        )

        assertEquals(false, store.read())
    }

    @Test
    fun writePersistsTheBoolean() {
        var stored: String? = null
        val store = PreferenceMaterialYouSettingStore(
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
        val store = PreferenceMaterialYouSettingStore(
            readStoredValue = { null },
            writeStoredValue = { error("disk full") }
        )

        store.write(true)
    }

    @Test
    fun materialYouIsAvailableFromApi31() {
        assertFalse(isMaterialYouAvailable(26))
        assertFalse(isMaterialYouAvailable(30))
        assertTrue(isMaterialYouAvailable(31))
        assertTrue(isMaterialYouAvailable(36))
    }

    private fun storeReturning(value: String?) = PreferenceMaterialYouSettingStore(
        readStoredValue = { value },
        writeStoredValue = {}
    )
}
