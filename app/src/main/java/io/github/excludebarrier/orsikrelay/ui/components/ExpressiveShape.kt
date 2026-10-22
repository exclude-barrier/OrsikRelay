package io.github.excludebarrier.orsikrelay.ui.components

import androidx.compose.foundation.shape.GenericShape
import androidx.compose.ui.graphics.Shape
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The wavy outlines of the design: a circle whose radius follows a cosine
 * with [lobes] waves of relative [depth]. The values are fitted to the
 * design's sampled SVG paths (max. deviation below 0.15 viewport units), so
 * one formula replaces three hard-coded point lists. Plain [GenericShape],
 * no experimental shape API.
 */
enum class ExpressiveShape(val lobes: Int, val depth: Float) {
    /** Avatar and send button: nine shallow waves. */
    Cookie(9, 0.068f),

    /** Approval and welcome badge: eight shallow waves. */
    Sunny(8, 0.077f),

    /** The loading star: seven deep, soft waves. */
    Soft7(7, 0.226f);

    /** The outline, filling the smaller side of the layout box. */
    val shape: Shape by lazy { waveShape(lobes, depth) }
}

private fun waveShape(lobes: Int, depth: Float): Shape = GenericShape { size, _ ->
    val centerX = size.width / 2f
    val centerY = size.height / 2f
    val base = min(size.width, size.height) / 2f / (1f + depth)
    val steps = 180
    for (step in 0..steps) {
        // Start at the top, like the design's paths, so a lobe points up.
        val angle = 2.0 * PI * step / steps - PI / 2
        val radius = base * (1f + depth * cos(lobes * (angle + PI / 2)).toFloat())
        val x = centerX + radius * cos(angle).toFloat()
        val y = centerY + radius * sin(angle).toFloat()
        if (step == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}
