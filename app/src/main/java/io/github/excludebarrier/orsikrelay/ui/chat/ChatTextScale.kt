package io.github.excludebarrier.orsikrelay.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/**
 * Multiplier for the chat message font size.
 *
 * It is applied on top of the system font scale (never instead of it) and
 * only to the content of chat messages. Default 1.0 keeps every existing
 * caller unchanged.
 */
val LocalChatTextScale = staticCompositionLocalOf { 1f }

/**
 * Provides the scaled font density to [content].
 *
 * `sp`-sized text inside content grows with [scale], while `dp` sizes
 * (padding, code-block chrome) stay untouched.
 */
@Composable
internal fun ChatTextScaledContent(
    scale: Float,
    content: @Composable () -> Unit
) {
    if (scale == 1f) {
        content()
        return
    }
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(
            density = density.density,
            fontScale = density.fontScale * scale
        )
    ) {
        content()
    }
}
