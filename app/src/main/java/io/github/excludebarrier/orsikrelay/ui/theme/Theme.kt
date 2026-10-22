package io.github.excludebarrier.orsikrelay.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * User-facing "Orsik Relay" brand palette (design v2, 2026-10-22).
 *
 * The neutrals follow the "GitHub chrome" preset: the page is the
 * background (#FFFFFF / #0D1117), cards sit one step up on
 * surfaceContainerLow (#F6F8FA / #161B22), inputs and icon containers on
 * surfaceContainer, and badges on surfaceContainerHighest. The accent is the
 * brand blue #0053FD, lifted to #5B8FFF on dark so it stays readable.
 *
 * This is a user-side brand only, not an official Hermes/Nous identity.
 * Status colours (reachable, warning) live in [OrsikStatusColors], not here.
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF0053FD),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD3E2FF),
    onPrimaryContainer = Color(0xFF001A4D),
    secondary = Color(0xFF0053FD),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD3E2FF),
    onSecondaryContainer = Color(0xFF001A4D),
    tertiary = Color(0xFF0969DA),
    onTertiary = Color(0xFFFFFFFF),
    error = Color(0xFFCF222E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFE81123),
    onErrorContainer = Color(0xFFFFFFFF),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF1F2328),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1F2328),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF6F8FA),
    surfaceContainer = Color(0xFFEEF1F4),
    surfaceContainerHigh = Color(0xFFE7EBEF),
    surfaceContainerHighest = Color(0xFFE1E6EB),
    surfaceVariant = Color(0xFFE1E6EB),
    onSurfaceVariant = Color(0xFF57606A),
    outline = Color(0xFF7D8590),
    outlineVariant = Color(0xFFD0D7DE),
    inverseSurface = Color(0xFF1F2328),
    inverseOnSurface = Color(0xFFF6F8FA),
    surfaceTint = Color(0xFF0053FD)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF5B8FFF),
    onPrimary = Color(0xFF06122E),
    primaryContainer = Color(0xFF22396A),
    onPrimaryContainer = Color(0xFFE3EBFF),
    secondary = Color(0xFF5B8FFF),
    onSecondary = Color(0xFF06122E),
    secondaryContainer = Color(0xFF22396A),
    onSecondaryContainer = Color(0xFFE3EBFF),
    tertiary = Color(0xFF58A6FF),
    onTertiary = Color(0xFF06122E),
    error = Color(0xFFF85149),
    onError = Color(0xFF161B22),
    errorContainer = Color(0xFFDA3633),
    onErrorContainer = Color(0xFFFFFFFF),
    background = Color(0xFF0D1117),
    onBackground = Color(0xFFE6EDF3),
    surface = Color(0xFF0D1117),
    onSurface = Color(0xFFE6EDF3),
    surfaceContainerLowest = Color(0xFF010409),
    surfaceContainerLow = Color(0xFF161B22),
    surfaceContainer = Color(0xFF1C2128),
    surfaceContainerHigh = Color(0xFF22272E),
    surfaceContainerHighest = Color(0xFF2D333B),
    surfaceVariant = Color(0xFF2D333B),
    onSurfaceVariant = Color(0xFFA8B0BA),
    outline = Color(0xFF6E7681),
    outlineVariant = Color(0xFF30363D),
    inverseSurface = Color(0xFFE6EDF3),
    inverseOnSurface = Color(0xFF1F2328),
    surfaceTint = Color(0xFF5B8FFF)
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
 *
 * [dynamicColor] opts into Material You: the scheme is then derived from the
 * user's wallpaper on Android 12+. The explicit brand palette — including its
 * tuned `surfaceContainer*` neutral ramp — stays in effect when the switch is
 * off or the platform is older than API 31.
 */
@Composable
fun HermesTheme(
    darkTheme: Boolean? = null,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val resolvedDark = darkTheme ?: isSystemInDarkTheme()
    val context = LocalContext.current
    val colorScheme = if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (resolvedDark) {
            dynamicDarkColorScheme(context)
        } else {
            dynamicLightColorScheme(context)
        }
    } else if (resolvedDark) {
        DarkColors
    } else {
        LightColors
    }
    // Status colours stay the brand's in both palettes: green/amber carry
    // meaning, so Material You must not recolour them.
    CompositionLocalProvider(
        LocalOrsikStatusColors provides if (resolvedDark) DarkStatusColors else LightStatusColors
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            shapes = HermesShapes,
            content = content
        )
    }
}
