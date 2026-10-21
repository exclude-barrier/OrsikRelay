package io.github.excludebarrier.orsikrelay.ui.home

import androidx.compose.foundation.shape.GenericShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Wavy "cookie" outline for the agent avatar and the send button: a circle
 * whose radius is modulated by a cosine with [lobes] waves. Plain
 * [GenericShape], no experimental shape API.
 */
internal fun cookieShape(lobes: Int = 9, depth: Float = 0.07f) = GenericShape { size, _ ->
    val centerX = size.width / 2f
    val centerY = size.height / 2f
    val base = min(size.width, size.height) / 2f / (1f + depth)
    val steps = 180
    for (step in 0..steps) {
        val angle = 2.0 * PI * step / steps
        val radius = base * (1f + depth * cos(lobes * angle).toFloat())
        val x = centerX + radius * cos(angle).toFloat()
        val y = centerY + radius * sin(angle).toFloat()
        if (step == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

/*
 * Folder and Code are Material Icons, Copyright Google LLC,
 * licensed under the Apache License 2.0 (https://fonts.google.com/icons).
 */
internal val FolderIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Filled.Folder",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(10f, 4f)
            horizontalLineTo(4f)
            curveToRelative(-1.1f, 0f, -1.99f, 0.9f, -1.99f, 2f)
            lineTo(2f, 18f)
            curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f)
            horizontalLineToRelative(16f)
            curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
            verticalLineTo(8f)
            curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f)
            horizontalLineToRelative(-8f)
            lineToRelative(-2f, -2f)
            close()
        }
    }.build()
}

internal val CodeIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Filled.Code",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(9.4f, 16.6f)
            lineTo(4.8f, 12f)
            lineToRelative(4.6f, -4.6f)
            lineTo(8f, 6f)
            lineToRelative(-6f, 6f)
            lineToRelative(6f, 6f)
            lineToRelative(1.4f, -1.4f)
            close()
            moveTo(14.6f, 16.6f)
            lineToRelative(4.6f, -4.6f)
            lineToRelative(-4.6f, -4.6f)
            lineTo(16f, 6f)
            lineToRelative(6f, 6f)
            lineToRelative(-6f, 6f)
            lineToRelative(-1.4f, -1.4f)
            close()
        }
    }.build()
}
