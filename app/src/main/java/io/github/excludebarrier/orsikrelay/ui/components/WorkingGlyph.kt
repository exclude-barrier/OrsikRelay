package io.github.excludebarrier.orsikrelay.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

internal const val TAG_WORKING_GLYPH = "orsik_working_glyph"

/** Glow of a runner without the baton. */
internal const val REST_GLOW = 0.3f

/** Wave depth of the SOFT7 star in the middle. */
private const val STAR_DEPTH = 0.226f

/**
 * Pace of the relay per turn phase: [orbit] scales the runners' circle,
 * [spinMs] is one lap, [handOffMs] the time a runner holds the baton and
 * [arc] the share of that time the light needs to reach the next runner.
 */
enum class RelayPhase(val orbit: Float, val spinMs: Long, val handOffMs: Long, val arc: Float) {
    Working(orbit = 1f, spinMs = 3_200, handOffMs = 600, arc = 0.35f),
    Thinking(orbit = 0.82f, spinMs = 5_000, handOffMs = 1_000, arc = 0.3f),
    Tool(orbit = 1f, spinMs = 2_000, handOffMs = 380, arc = 0.55f)
}

/**
 * One frame of the relay: the runners' [angles] (radians, a third of a
 * circle apart), their [glow] (1 = holds the baton), the runner that
 * holds it and the light arc on its way to the next one ([arcStart] and
 * [arcSweep] in radians; sweep 0 = no arc).
 */
internal data class RelayFrame(
    val angles: List<Float>,
    val glow: List<Float>,
    val holder: Int,
    val arcStart: Float,
    val arcSweep: Float,
    val starAngle: Float
)

/** Pure frame maths, so the hand-off can be tested without drawing. */
internal fun relayFrame(timeMs: Long, phase: RelayPhase, still: Boolean = false): RelayFrame {
    val third = (2 * PI / 3).toFloat()
    if (still) {
        val top = (-PI / 2).toFloat()
        return RelayFrame(
            angles = List(3) { top + it * third },
            glow = listOf(1f, REST_GLOW, REST_GLOW),
            holder = 0,
            arcStart = top,
            arcSweep = 0f,
            starAngle = 0.31f
        )
    }
    val theta = (-PI / 2 + 2 * PI * Math.floorMod(timeMs, phase.spinMs) / phase.spinMs).toFloat()
    // floorMod: a negative clock (preview, replay) must stay in 0..2.
    val holder = Math.floorMod(timeMs / phase.handOffMs, 3L).toInt()
    val progress = Math.floorMod(timeMs, phase.handOffMs).toFloat() / phase.handOffMs
    val passing = progress > 1f - phase.arc
    val f = if (passing) (progress - (1f - phase.arc)) / phase.arc else 0f
    val glow = List(3) { k ->
        when {
            k == holder -> 1f - (1f - REST_GLOW) * f
            k == (holder + 1) % 3 -> REST_GLOW + (1f - REST_GLOW) * f
            else -> REST_GLOW
        }
    }
    val angles = List(3) { theta + it * third }
    val tail = (f - 0.45f).coerceAtLeast(0f)
    return RelayFrame(
        angles = angles,
        glow = glow,
        holder = holder,
        arcStart = angles[holder] + third * tail,
        arcSweep = if (passing) third * (f - tail) else 0f,
        starAngle = (2 * PI * Math.floorMod(timeMs, 9_000L) / 9_000).toFloat()
    )
}

/**
 * Orsik's "Hermes is working" mark, the relay: a quiet seven-lobed star
 * in the middle, three runners circling it, and the light (the baton)
 * passed from runner to runner along a short arc. [phase] sets the pace:
 * calm while Hermes thinks, quick while a tool runs. With "Remove
 * animations" the runners rest, one of them lit.
 */
@Composable
fun WorkingGlyph(
    modifier: Modifier = Modifier,
    phase: RelayPhase = RelayPhase.Working,
    size: Dp = 28.dp
) {
    val reducedMotion = rememberReducedMotion()
    val color = MaterialTheme.colorScheme.primary
    val timeMs by produceState(0L, reducedMotion) {
        if (reducedMotion) return@produceState
        val start = withFrameMillis { it }
        while (true) {
            withFrameMillis { value = it - start }
        }
    }
    Canvas(modifier = modifier.size(size).testTag(TAG_WORKING_GLYPH)) {
        drawRelay(relayFrame(timeMs, phase, still = reducedMotion), phase, color)
    }
}

private fun DrawScope.drawRelay(frame: RelayFrame, phase: RelayPhase, color: Color) {
    val half = min(size.width, size.height) / 2f
    val center = Offset(size.width / 2f, size.height / 2f)
    rotate(Math.toDegrees(frame.starAngle.toDouble()).toFloat(), center) {
        drawPath(softStar(center, half * 0.36f), color.copy(alpha = 0.45f))
    }
    val radius = half * 0.68f * phase.orbit
    if (frame.arcSweep > 0f) {
        drawArc(
            color = color.copy(alpha = 0.85f),
            startAngle = Math.toDegrees(frame.arcStart.toDouble()).toFloat(),
            sweepAngle = Math.toDegrees(frame.arcSweep.toDouble()).toFloat(),
            useCenter = false,
            topLeft = Offset(center.x - radius, center.y - radius),
            size = Size(radius * 2, radius * 2),
            style = Stroke(width = half * 0.09f, cap = StrokeCap.Round)
        )
    }
    frame.angles.forEachIndexed { k, angle ->
        val glow = frame.glow[k]
        drawCircle(
            color = color.copy(alpha = glow),
            radius = half * (0.12f + 0.06f * glow),
            center = Offset(center.x + radius * cos(angle), center.y + radius * sin(angle))
        )
    }
}

private fun softStar(center: Offset, radius: Float): Path {
    val base = radius / (1f + STAR_DEPTH)
    val path = Path()
    for (step in 0..140) {
        val a = 2.0 * PI * step / 140
        val r = base * (1f + STAR_DEPTH * cos(7 * a).toFloat())
        val x = center.x + r * cos(a).toFloat()
        val y = center.y + r * sin(a).toFloat()
        if (step == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    return path
}
