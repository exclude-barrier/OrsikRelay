package io.github.excludebarrier.orsikrelay.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

internal const val TAG_WORKING_GLYPH = "orsik_working_glyph"

/** Wave depth of the resting star (the design's SOFT7). */
private const val REST_DEPTH = 0.226f

/**
 * Orsik's own "Hermes is working" mark: the seven-lobed star of the
 * loading indicator, slowly turning while its lobes breathe in and out.
 * With "Remove animations" it rests as the plain SOFT7 star.
 */
@Composable
fun WorkingGlyph(modifier: Modifier = Modifier, size: Dp = 20.dp) {
    val reducedMotion = rememberReducedMotion()
    val color = MaterialTheme.colorScheme.primary
    val angle: Float
    val depth: Float
    if (reducedMotion) {
        angle = 18f
        depth = REST_DEPTH
    } else {
        val transition = rememberInfiniteTransition(label = "workingGlyph")
        angle = transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(4_000, easing = LinearEasing), RepeatMode.Restart),
            label = "workingGlyphAngle"
        ).value
        depth = transition.animateFloat(
            initialValue = 0.08f,
            targetValue = 0.26f,
            animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "workingGlyphDepth"
        ).value
    }
    Canvas(modifier = modifier.size(size).testTag(TAG_WORKING_GLYPH)) {
        val center = Offset(this.size.width / 2f, this.size.height / 2f)
        val base = min(this.size.width, this.size.height) / 2f / (1f + depth)
        val path = Path()
        val steps = 140
        for (step in 0..steps) {
            val a = 2.0 * PI * step / steps
            val r = base * (1f + depth * cos(7 * a).toFloat())
            val x = center.x + r * cos(a).toFloat()
            val y = center.y + r * sin(a).toFloat()
            if (step == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        rotate(angle, center) { drawPath(path, color) }
    }
}
