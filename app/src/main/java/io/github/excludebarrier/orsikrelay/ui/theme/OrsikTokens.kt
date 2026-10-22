package io.github.excludebarrier.orsikrelay.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import io.github.excludebarrier.orsikrelay.R
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Status colours of the design: "reachable" green and the approval amber.
 * Kept out of the Material colour scheme so Material You never recolours a
 * meaning.
 */
@Immutable
data class OrsikStatusColors(
    val ok: Color,
    val okText: Color,
    val okContainer: Color,
    val warn: Color,
    val onWarn: Color,
    val warnContainer: Color,
    val onWarnContainer: Color
)

internal val DarkStatusColors = OrsikStatusColors(
    ok = Color(0xFF3FB950),
    okText = Color(0xFF7EE787),
    okContainer = Color(0xFF12301B),
    warn = Color(0xFFF2B530),
    onWarn = Color(0xFF2A1C00),
    warnContainer = Color(0xFF45330A),
    onWarnContainer = Color(0xFFFFE7A8)
)

internal val LightStatusColors = OrsikStatusColors(
    ok = Color(0xFF1A7F37),
    okText = Color(0xFF116329),
    okContainer = Color(0xFFDAFBE1),
    warn = Color(0xFF9A6700),
    onWarn = Color(0xFFFFFFFF),
    warnContainer = Color(0xFFFFF1C2),
    onWarnContainer = Color(0xFF4D3300)
)

val LocalOrsikStatusColors = staticCompositionLocalOf { LightStatusColors }

/** Access to the design tokens that Material 3 has no slot for. */
object OrsikTheme {
    val statusColors: OrsikStatusColors
        @Composable
        @ReadOnlyComposable
        get() = LocalOrsikStatusColors.current
}

/** Text styles of the design; colours are applied at the call site. */
/**
 * Roboto Mono (design v2) for code: bundled as res/font/roboto_mono.ttf from
 * github.com/googlefonts/RobotoMono, SIL Open Font License 1.1 (text in
 * assets/licenses/roboto-mono-OFL.txt).
 */
val OrsikMono = FontFamily(Font(R.font.roboto_mono, FontWeight.Normal))

object OrsikType {
    val screenTitle = TextStyle(fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.02).em)
    val compactTitle = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold)
    val detailTitle = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold)
    val sectionTitle = TextStyle(fontSize = 19.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold)
    val listTitle = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
    val meta = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)
    val groupLabel = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold)
    val badge = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.03.em)
}

/** Corner radii of the design. */
object OrsikShapes {
    val card = RoundedCornerShape(24.dp)
    val iconContainer = RoundedCornerShape(14.dp)
    val dock = RoundedCornerShape(30.dp)
    val chip = RoundedCornerShape(12.dp)
    val bottomArea = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    val badge = RoundedCornerShape(6.dp)
    val pill = RoundedCornerShape(percent = 50)
    val dialog = RoundedCornerShape(24.dp)
}

/** Dialogs of design v2: card radius and the card surface, flat (no tonal tint). */
object OrsikDialog {
    val shape = OrsikShapes.dialog
    val containerColor: Color
        @Composable
        @ReadOnlyComposable
        get() = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerLow
    val tonalElevation = 0.dp
}
