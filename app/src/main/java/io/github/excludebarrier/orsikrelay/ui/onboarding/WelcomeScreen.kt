package io.github.excludebarrier.orsikrelay.ui.onboarding

import android.content.pm.PackageManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.excludebarrier.orsikrelay.R
import io.github.excludebarrier.orsikrelay.ui.components.ExpressiveShape
import io.github.excludebarrier.orsikrelay.ui.components.OrsikIcons
import io.github.excludebarrier.orsikrelay.ui.theme.OrsikShapes
import io.github.excludebarrier.orsikrelay.ui.theme.OrsikType

internal const val WELCOME_SCREEN_TAG = "welcome_screen"
internal const val WELCOME_SCAN_TAG = "welcome_scan"
internal const val WELCOME_ENTER_CODE_TAG = "welcome_enter_code"

/**
 * First screen while no server is saved: what Orsik Relay is, the three
 * steps, and the two ways into the existing pairing flow. [onScan] opens
 * the onboarding form with its QR scanner already open (hidden on devices
 * without a camera); [onEnterCode] opens the same form for a pairing code
 * or an API key. Nothing here connects or stores anything.
 */
@Composable
fun WelcomeScreen(
    onScan: () -> Unit,
    onEnterCode: () -> Unit,
    modifier: Modifier = Modifier,
    cameraAvailable: Boolean = rememberCameraAvailable()
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 24.dp, end = 24.dp, top = 48.dp, bottom = 28.dp)
            .testTag(WELCOME_SCREEN_TAG)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(72.dp)
                .clip(ExpressiveShape.Cookie.shape)
                .background(MaterialTheme.colorScheme.primaryContainer)
        ) {
            Icon(OrsikIcons.Relay, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
        }
        Text(
            text = stringResource(R.string.welcome_title),
            style = OrsikType.screenTitle,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier
                .padding(top = 28.dp)
                .semantics { heading() }
        )
        Text(
            text = stringResource(R.string.welcome_text),
            fontSize = 16.sp,
            lineHeight = 24.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp)
        )
        Column(verticalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.padding(top = 32.dp)) {
            WelcomeStep(1, R.string.welcome_step1_title, R.string.welcome_step1_text)
            WelcomeStep(2, R.string.welcome_step2_title, R.string.welcome_step2_text)
            WelcomeStep(3, R.string.welcome_step3_title, R.string.welcome_step3_text)
        }
        Spacer(Modifier.weight(1f).heightIn(min = 32.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (cameraAvailable) {
                Button(
                    onClick = onScan,
                    shape = OrsikShapes.pill,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 60.dp)
                        .testTag(WELCOME_SCAN_TAG)
                ) {
                    Icon(OrsikIcons.Scan, contentDescription = null, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.welcome_scan), fontSize = 17.sp, fontWeight = FontWeight.Bold)
                }
            }
            OutlinedButton(
                onClick = onEnterCode,
                shape = OrsikShapes.pill,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .testTag(WELCOME_ENTER_CODE_TAG)
            ) {
                Icon(OrsikIcons.Key, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.welcome_enter_code), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun WelcomeStep(number: Int, title: Int, text: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(36.dp)
                .clip(ExpressiveShape.Sunny.shape)
                .background(MaterialTheme.colorScheme.primaryContainer)
        ) {
            Text(
                text = number.toString(),
                fontSize = 14.sp,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
        Column(modifier = Modifier.padding(top = 4.dp)) {
            Text(stringResource(title), style = OrsikType.listTitle, color = MaterialTheme.colorScheme.onBackground)
            Text(
                stringResource(text),
                fontSize = 13.5.sp,
                lineHeight = 19.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun rememberCameraAvailable(): Boolean {
    val context = LocalContext.current
    return remember(context) { context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) }
}
