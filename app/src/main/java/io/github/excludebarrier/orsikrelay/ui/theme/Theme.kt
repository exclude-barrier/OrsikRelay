package io.github.excludebarrier.orsikrelay.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * User-facing "Orsik Relay" brand palette.
 *
 * The neutrals follow the Nous/Hermes desktop "GitHub chrome" preset
 * (light #FFFFFF background / #F6F8FA card, dark #0D1117 / #010409) and the
 * accent is the Nous blue. Light uses the brand blue #0053FD; dark lifts the
 * same hue to #4A84FE so it stays AA-contrast on the near-black surface.
 *
 * This is a user-side brand only. It is an inspiration, not an official
 * Hermes/Nous identity: the app carries no logo and no affiliation claim.
 * The Hermes API types and the `io.github.excludebarrier.orsikrelay` package stay
 * untouched; only the visible brand changes.
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF0053FD),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDEEAFF),
    onPrimaryContainer = Color(0xFF1F2328),
    secondary = Color(0xFFDEEAFF),
    onSecondary = Color(0xFF1F2328),
    secondaryContainer = Color(0xFFE3EDFF),
    onSecondaryContainer = Color(0xFF1F2328),
    tertiary = Color(0xFF0969DA),
    onTertiary = Color(0xFFFFFFFF),
    error = Color(0xFFCF222E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFE81123),
    onErrorContainer = Color(0xFFFFFFFF),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF1F2328),
    surface = Color(0xFFF6F8FA),
    onSurface = Color(0xFF1F2328),
    surfaceVariant = Color(0xFFE3EDFF),
    onSurfaceVariant = Color(0xFF57606A),
    outline = Color(0xFF7D8590),
    outlineVariant = Color(0xFFD8DEE3),
    inverseSurface = Color(0xFF1F2328),
    inverseOnSurface = Color(0xFFF6F8FA),
    surfaceTint = Color(0xFF0053FD)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF4A84FE),
    onPrimary = Color(0xFF161616),
    primaryContainer = Color(0xFF1D2E4F),
    onPrimaryContainer = Color(0xFFE6EDF3),
    secondary = Color(0xFF1D2E4F),
    onSecondary = Color(0xFFE6EDF3),
    secondaryContainer = Color(0xFF17243A),
    onSecondaryContainer = Color(0xFFE6EDF3),
    tertiary = Color(0xFF58A6FF),
    onTertiary = Color(0xFF161616),
    error = Color(0xFFF85149),
    onError = Color(0xFF161B22),
    errorContainer = Color(0xFFDA3633),
    onErrorContainer = Color(0xFFFFFFFF),
    background = Color(0xFF0D1117),
    onBackground = Color(0xFFE6EDF3),
    surface = Color(0xFF010409),
    onSurface = Color(0xFFE6EDF3),
    surfaceVariant = Color(0xFF17243A),
    onSurfaceVariant = Color(0xFFA8B0BA),
    outline = Color(0xFF6E7681),
    outlineVariant = Color(0xFF21262C),
    inverseSurface = Color(0xFFE6EDF3),
    inverseOnSurface = Color(0xFF1F2328),
    surfaceTint = Color(0xFF4A84FE)
)

private val HermesShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

/**
 * App theme. [darkTheme] is an explicit override; when it is `null` (the
 * default) the system light/dark setting is followed. Keeping the override
 * optional means a persistent theme switcher can later pass a stored value
 * here without touching any call site.
 */
@Composable
fun HermesTheme(
    darkTheme: Boolean? = null,
    content: @Composable () -> Unit
) {
    val resolvedDark = darkTheme ?: isSystemInDarkTheme()
    val colorScheme = if (resolvedDark) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colorScheme,
        shapes = HermesShapes,
        content = content
    )
}
