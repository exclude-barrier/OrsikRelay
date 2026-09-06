package io.github.excludebarrier.orsikrelay.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Shared surface chrome for the "Orsik Relay" brand.
 *
 * A rounded [background] panel with a 1dp [MaterialTheme.colorScheme.outline]
 * border and inner [contentPadding], matching the GitHub-chrome neutral
 * palette. The corner radius and border come from the theme so the light and
 * dark variants stay consistent. Composable because it reads the active
 * color scheme; chain it in a `Modifier`. Pass 0.dp for [contentPadding]
 * when the caller pads its content itself (e.g. clickable session cards).
 */
@Composable
fun Modifier.brandSurface(
    background: Color = MaterialTheme.colorScheme.surface,
    contentPadding: Dp = 16.dp
): Modifier =
    this
        .background(background, shape = MaterialTheme.shapes.medium)
        .border(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outline,
            shape = MaterialTheme.shapes.medium
        )
        .padding(contentPadding)
