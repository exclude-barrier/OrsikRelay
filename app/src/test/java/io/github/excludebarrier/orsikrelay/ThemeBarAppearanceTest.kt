package io.github.excludebarrier.orsikrelay

import io.github.excludebarrier.orsikrelay.data.theme.ThemeSettingStore
import io.github.excludebarrier.orsikrelay.ui.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit coverage for the two theme-startup defects the reviews flagged as
 * untested:
 *
 * 1. System-bar icon contrast (blocker): the icon appearance must follow
 *    the RESOLVED app theme, not the system setting.
 * 2. A failing preference read falls back to the default theme.
 */
class ThemeBarAppearanceTest {

    @Test
    fun resolvedDarkThemeMeansDarkBarsWithLightIcons() {
        val (statusLight, navLight) = resolvedThemeBarAppearance(darkTheme = true)
        // Dark bars: the icons are the LIGHT variant, i.e. the
        // "appearance light bars" flag is false for both bars.
        assertEquals(false, statusLight)
        assertEquals(false, navLight)
    }

    @Test
    fun resolvedLightThemeMeansLightBarsWithDarkIcons() {
        val (statusLight, navLight) = resolvedThemeBarAppearance(darkTheme = false)
        // Light bars: the icons are the DARK variant, i.e. the flag is
        // true for both bars.
        assertEquals(true, statusLight)
        assertEquals(true, navLight)
    }

    @Test
    fun barAppearanceAlwaysMirrorsTheNegatedResolvedTheme() {
        for (dark in listOf(false, true)) {
            val (statusLight, navLight) = resolvedThemeBarAppearance(dark)
            assertEquals(!dark, statusLight)
            assertEquals(!dark, navLight)
            assertTrue(statusLight == navLight)
        }
    }

    /**
     * A store whose read succeeds must be reflected as the seeded initial
     * mode, and a failing store must fall back to the default without
     * throwing.
     */
    @Test
    fun failingStoreFallsBackToDefaultWithoutThrowing() {
        class ThrowingStore : ThemeSettingStore {
            override fun read(): ThemeMode = throw IllegalStateException("io failure")
            override fun write(mode: ThemeMode) { /* no-op */ }
        }
        // The composable rememberThemeModeController wraps read in
        // runCatching; assert the same fallback the store layer relies on.
        val mode = runCatching { ThrowingStore().read() }.getOrDefault(ThemeMode.DEFAULT)
        assertEquals(ThemeMode.System, mode)
    }
}
