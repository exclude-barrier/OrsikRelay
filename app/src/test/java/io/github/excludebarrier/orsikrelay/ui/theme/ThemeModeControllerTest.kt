package io.github.excludebarrier.orsikrelay.ui.theme

import androidx.compose.runtime.mutableStateOf
import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeModeControllerTest {

    @Test
    fun rapidSelectionsPersistInCallOrderAndKeepLastMode() {
        val writes = mutableListOf<ThemeMode>()
        val controller = ThemeModeController(mutableStateOf(ThemeMode.System)) {
            writes += it
        }

        controller.select(ThemeMode.Light)
        controller.select(ThemeMode.Dark)

        assertEquals(listOf(ThemeMode.Light, ThemeMode.Dark), writes)
        assertEquals(ThemeMode.Dark, controller.mode)
    }
}
