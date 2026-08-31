package io.github.excludebarrier.orsikrelay.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme()
private val DarkColors = darkColorScheme()

/**
 * Material 3 theme. Mission 001 keeps the default M3 color schemes;
 * a branded palette can be introduced later without touching call sites.
 */
@Composable
fun HermesTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
