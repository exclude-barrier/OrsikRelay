package io.github.excludebarrier.orsikrelay.ui.components

import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal const val TAG_LOADING_INDICATOR = "orsik_loading_indicator"

/** The resting angle of the star, as drawn in the design. */
private const val STATIC_ANGLE = 18f

/**
 * The design's "working" indicator: a [ExpressiveShape.Soft7] star in
 * primary, turning inside a primaryContainer circle. With "Remove
 * animations" (animator duration scale 0) the star rests at its design
 * angle instead of spinning.
 */
@Composable
fun OrsikLoadingIndicator(
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    contentDescription: String? = null
) {
    val reducedMotion = rememberReducedMotion()
    val angle = if (reducedMotion) {
        STATIC_ANGLE
    } else {
        val transition = rememberInfiniteTransition(label = "orsikLoading")
        transition.animateFloat(
            initialValue = STATIC_ANGLE,
            targetValue = STATIC_ANGLE + 360f,
            animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart),
            label = "orsikLoadingAngle"
        ).value
    }
    val star = MaterialTheme.colorScheme.primary
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
                if (contentDescription != null) this.contentDescription = contentDescription
            }
            .testTag(TAG_LOADING_INDICATOR)
    ) {
        Box(
            modifier = Modifier
                .size(size * 0.7f)
                .graphicsLayer { rotationZ = angle }
                .clip(ExpressiveShape.Soft7.shape)
                .background(star)
        )
    }
}

/** True when the user switched off animations in the system settings. */
@Composable
internal fun rememberReducedMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) {
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}
