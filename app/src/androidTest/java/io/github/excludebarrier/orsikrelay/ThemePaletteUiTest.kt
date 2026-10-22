package io.github.excludebarrier.orsikrelay

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.excludebarrier.orsikrelay.ui.theme.HermesTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/**
 * Brand palette contract for the user-facing "Orsik Relay" client.
 *
 * Design v2 (2026-10-22): the page is background = surface (#FFFFFF /
 * #0D1117), cards sit on surfaceContainerLow (#F6F8FA / #161B22). Light
 * uses Nous blue #0053FD as primary, dark the lifted #5B8FFF (AA on
 * near-black). The palette is an
 * inspiration, not an official Hermes/Nous identity: no logo and no
 * affiliation claim anywhere in the UI.
 *
 * The theme API stays deliberately small: [HermesTheme] takes an optional
 * explicit [Boolean] and defaults to the system dark mode, so a persistent
 * theme switcher can later pass an explicit value without touching call
 * sites.
 */
class ThemePaletteUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun lightSchemeUsesWhiteBackgroundAndNousBlue() {
        val capture = readPalette(darkTheme = false)
        assertEquals(Color(0xFFFFFFFF), capture.background)
        assertEquals(Color(0xFFFFFFFF), capture.surface)
        assertEquals(Color(0xFF0053FD), capture.primary)
        assertEquals(Color(0xFFFFFFFF), capture.onPrimary)
        assertEquals(Color(0xFFE1E6EB), capture.surfaceVariant)
        assertEquals(Color(0xFF7D8590), capture.outline)
        assertEquals(Color(0xFFCF222E), capture.error)
    }

    @Test
    fun darkSchemeUsesGithubDarkNeutralsAndLiftedBlue() {
        val capture = readPalette(darkTheme = true)
        assertEquals(Color(0xFF0D1117), capture.background)
        assertEquals(Color(0xFF0D1117), capture.surface)
        assertEquals(Color(0xFF5B8FFF), capture.primary)
        assertEquals(Color(0xFF06122E), capture.onPrimary)
        assertEquals(Color(0xFF2D333B), capture.surfaceVariant)
        assertEquals(Color(0xFF6E7681), capture.outline)
        assertEquals(Color(0xFFF85149), capture.error)
    }

    @Test
    fun defaultThemeFollowsSystemDarkMode() {
        val capture = readPalette(darkTheme = null)
        val expectedBackground = if (capture.systemDark) {
            Color(0xFF0D1117)
        } else {
            Color(0xFFFFFFFF)
        }
        assertEquals(expectedBackground, capture.background)
    }

    /**
     * Material You must not lose the warning semantics of the approval
     * dialog: its "Allow" button is painted with the scheme's `error`
     * colour, so a dynamic scheme still has to keep `error` visibly distinct
     * from `primary` and readable with `onError`.
     */
    @Test
    fun dynamicThemeUsesTheWallpaperSchemeAndKeepsErrorAsAWarning() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val expectedLight = dynamicLightColorScheme(context)
        val expectedDark = dynamicDarkColorScheme(context)

        var capturedPrimary: Color? = null
        var capturedError: Color? = null
        composeRule.setContent {
            HermesTheme(darkTheme = false, dynamicColor = true) {
                capturedPrimary = MaterialTheme.colorScheme.primary
                capturedError = MaterialTheme.colorScheme.error
            }
        }
        composeRule.waitForIdle()

        assertEquals(expectedLight.primary, capturedPrimary)
        assertEquals(expectedLight.error, capturedError)
        for ((label, scheme) in listOf(
            "dynamic light" to expectedLight,
            "dynamic dark" to expectedDark
        )) {
            assertNotEquals(label, scheme.primary, scheme.error)
            assertTrue(
                "$label error/onError ${contrast(scheme.error, scheme.onError)}:1 < 4.5",
                contrast(scheme.error, scheme.onError) >= 4.5
            )
        }
    }

    /**
     * WCAG-AA (4.5:1) for every foreground/background pair that carries
     * normal text. This is the property the palette must satisfy; the
     * hex assertions above pin the brand values, but only this test
     * would have caught the dark error pair (3.35:1) and the surface
     * variant text (4.17:1) regressions.
     */
    @Test
    fun textPairsMeetWcagAaContrast() {
        // Both schemes are read in ONE composition: a compose rule only
        // allows a single setContent per test, so the two palettes are
        // captured side by side rather than in a loop of setContent
        // calls.
        val (light, dark) = readBothPalettes()
        for ((label, c) in listOf("light" to light, "dark" to dark)) {
            assertTrue(
                "$label error/onError ${contrast(c.error, c.onError)}:1 < 4.5",
                contrast(c.error, c.onError) >= 4.5
            )
            assertTrue(
                "$label errorContainer/onErrorContainer ${contrast(c.errorContainer, c.onErrorContainer)}:1 < 4.5",
                contrast(c.errorContainer, c.onErrorContainer) >= 4.5
            )
            assertTrue(
                "$label surfaceVariant/onSurfaceVariant ${contrast(c.surfaceVariant, c.onSurfaceVariant)}:1 < 4.5",
                contrast(c.surfaceVariant, c.onSurfaceVariant) >= 4.5
            )
            assertTrue(
                "$label onSurface/onSurface ${contrast(c.surface, c.onSurface)}:1 < 4.5",
                contrast(c.surface, c.onSurface) >= 4.5
            )
        }
    }

    /** Reads the light and dark palettes in one composition. */
    private fun readBothPalettes(): Pair<PaletteCapture, PaletteCapture> {
        var light: PaletteCapture? = null
        var dark: PaletteCapture? = null
        composeRule.setContent {
            val systemDark = isSystemInDarkTheme()
            HermesTheme(darkTheme = false) {
                light = readColors(systemDark)
            }
            HermesTheme(darkTheme = true) {
                dark = readColors(systemDark)
            }
        }
        composeRule.waitForIdle()
        light?.let { l -> dark?.let { d -> return l to d } }
        fail("colors were not read from the composition")
        throw IllegalStateException("unreachable")
    }

    private data class PaletteCapture(
        val background: Color,
        val surface: Color,
        val primary: Color,
        val onPrimary: Color,
        val surfaceVariant: Color,
        val onSurfaceVariant: Color,
        val onSurface: Color,
        val outline: Color,
        val error: Color,
        val onError: Color,
        val errorContainer: Color,
        val onErrorContainer: Color,
        val systemDark: Boolean
    )

    private fun readPalette(darkTheme: Boolean?): PaletteCapture {
        var captured: PaletteCapture? = null
        composeRule.setContent {
            val systemDark = isSystemInDarkTheme()
            if (darkTheme == null) {
                HermesTheme {
                    captured = readColors(systemDark)
                }
            } else {
                val flag = darkTheme
                HermesTheme(darkTheme = flag) {
                    captured = readColors(systemDark)
                }
            }
        }
        composeRule.waitForIdle()
        if (captured != null) return captured
        fail("colors were not read from the composition")
        throw IllegalStateException("unreachable")
    }

    @Composable
    private fun readColors(systemDark: Boolean): PaletteCapture =
        with(MaterialTheme.colorScheme) {
            PaletteCapture(
                background = background,
                surface = surface,
                primary = primary,
                onPrimary = onPrimary,
                surfaceVariant = surfaceVariant,
                onSurfaceVariant = onSurfaceVariant,
                onSurface = onSurface,
                outline = outline,
                error = error,
                onError = onError,
                errorContainer = errorContainer,
                onErrorContainer = onErrorContainer,
                systemDark = systemDark
            )
        }

    /** WCAG 2.x relative-luminance contrast ratio of two colors. */
    private fun contrast(a: Color, b: Color): Double {
        val l1 = a.luminance()
        val l2 = b.luminance()
        val (hi, lo) = if (l1 >= l2) l1 to l2 else l2 to l1
        return (hi + 0.05) / (lo + 0.05)
    }
}
