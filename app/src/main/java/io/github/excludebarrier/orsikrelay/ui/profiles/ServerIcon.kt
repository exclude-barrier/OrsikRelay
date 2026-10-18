package io.github.excludebarrier.orsikrelay.ui.profiles

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/*
 * Dns is a Material Icon, Copyright Google LLC,
 * licensed under the Apache License 2.0 (https://fonts.google.com/icons).
 * Geometry taken from the official 24px source:
 * https://raw.githubusercontent.com/google/material-design-icons/master/src/action/dns/materialicons/24px.svg
 */
internal val DnsIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Filled.Dns",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(20f, 13f)
            horizontalLineTo(4f)
            curveToRelative(-0.55f, 0f, -1f, 0.45f, -1f, 1f)
            verticalLineToRelative(6f)
            curveToRelative(0f, 0.55f, 0.45f, 1f, 1f, 1f)
            horizontalLineToRelative(16f)
            curveToRelative(0.55f, 0f, 1f, -0.45f, 1f, -1f)
            verticalLineToRelative(-6f)
            curveToRelative(0f, -0.55f, -0.45f, -1f, -1f, -1f)
            close()
            moveTo(7f, 19f)
            curveToRelative(-1.1f, 0f, -2f, -0.9f, -2f, -2f)
            curveToRelative(0f, -1.1f, 0.9f, -2f, 2f, -2f)
            curveToRelative(1.1f, 0f, 2f, 0.9f, 2f, 2f)
            curveToRelative(0f, 1.1f, -0.9f, 2f, -2f, 2f)
            close()
            moveTo(20f, 3f)
            horizontalLineTo(4f)
            curveToRelative(-0.55f, 0f, -1f, 0.45f, -1f, 1f)
            verticalLineToRelative(6f)
            curveToRelative(0f, 0.55f, 0.45f, 1f, 1f, 1f)
            horizontalLineToRelative(16f)
            curveToRelative(0.55f, 0f, 1f, -0.45f, 1f, -1f)
            verticalLineTo(4f)
            curveToRelative(0f, -0.55f, -0.45f, -1f, -1f, -1f)
            close()
            moveTo(7f, 9f)
            curveToRelative(-1.1f, 0f, -2f, -0.9f, -2f, -2f)
            curveToRelative(0f, -1.1f, 0.9f, -2f, 2f, -2f)
            curveToRelative(1.1f, 0f, 2f, 0.9f, 2f, 2f)
            curveToRelative(0f, 1.1f, -0.9f, 2f, -2f, 2f)
            close()
        }
    }.build()
}
