package io.github.excludebarrier.orsikrelay.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure JVM contract for the theme mode resolution. No Android or Compose
 * dependencies: the mapping from (mode, systemDark) to the concrete dark
 * decision lives in [ThemeMode.resolve] and must stay deterministic.
 */
class ThemeModeTest {

    @Test
    fun systemModeFollowsTheSystemDarkFlag() {
        assertEquals(false, ThemeMode.System.resolve(systemDark = false))
        assertEquals(true, ThemeMode.System.resolve(systemDark = true))
    }

    @Test
    fun lightModeAlwaysResolvesLight() {
        assertEquals(false, ThemeMode.Light.resolve(systemDark = false))
        assertEquals(false, ThemeMode.Light.resolve(systemDark = true))
    }

    @Test
    fun darkModeAlwaysResolvesDark() {
        assertEquals(true, ThemeMode.Dark.resolve(systemDark = false))
        assertEquals(true, ThemeMode.Dark.resolve(systemDark = true))
    }

    @Test
    fun defaultModeIsSystem() {
        assertEquals(ThemeMode.System, ThemeMode.DEFAULT)
    }

    @Test
    fun allThreeModesArePresent() {
        assertEquals(listOf(ThemeMode.System, ThemeMode.Light, ThemeMode.Dark), ThemeMode.entries)
    }

    @Test
    fun fromStoredMapsKnownNamesCaseInsensitively() {
        assertEquals(ThemeMode.System, ThemeMode.fromStored("System"))
        assertEquals(ThemeMode.Light, ThemeMode.fromStored("light"))
        assertEquals(ThemeMode.Dark, ThemeMode.fromStored("DARK"))
    }

    @Test
    fun fromStoredFallsBackToDefaultForUnknownOrMissingValues() {
        assertEquals(ThemeMode.System, ThemeMode.fromStored(null))
        assertEquals(ThemeMode.System, ThemeMode.fromStored(""))
        assertEquals(ThemeMode.System, ThemeMode.fromStored("bogus"))
        assertEquals(ThemeMode.System, ThemeMode.fromStored("system "))
    }
}
