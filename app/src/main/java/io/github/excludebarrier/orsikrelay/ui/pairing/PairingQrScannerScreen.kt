package io.github.excludebarrier.orsikrelay.ui.pairing

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import io.github.excludebarrier.orsikrelay.R
import io.github.excludebarrier.orsikrelay.domain.device.PairingBootstrap
import io.github.excludebarrier.orsikrelay.domain.device.PairingDeepLink
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Full-screen QR scanner for the pairing form.
 *
 * A camera frame is decoded off the main thread by [QrImageAnalyzer], then
 * validated through the SAME [PairingDeepLink] parser as the `orsik://pair`
 * deep link. Only a fully valid pairing link is accepted; any other payload
 * (foreign URL, plain http, malformed code) keeps the scanner open with a
 * closed, input-free hint. Nothing is logged: the payload is a secret.
 *
 * [scanFrames] is the test seam for the scan source: when non-null, no camera
 * or permission is touched and the raw QR payloads are taken from the flow.
 */
@Composable
fun PairingQrScannerScreen(
    onScanned: (PairingBootstrap) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    scanFrames: Flow<String>? = null
) {
    val context = LocalContext.current
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }
    val currentOnScanned by rememberUpdatedState(onScanned)
    var invalidPayload by remember { mutableStateOf(false) }

    fun accept(rawPayload: String) {
        val bootstrap = PairingDeepLink.parse(rawPayload)
        if (bootstrap != null) {
            currentOnScanned(bootstrap)
        } else if (!invalidPayload) {
            invalidPayload = true
        }
    }

    if (scanFrames != null) {
        LaunchedEffect(scanFrames) {
            scanFrames.collect { accept(it) }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (scanFrames == null) {
            CameraScanner(
                // The analyzer runs on a background executor; state
                // changes hop back to the main thread.
                onPayload = { rawPayload -> mainExecutor.execute { accept(rawPayload) } }
            )
        }

        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.onboarding_scan_title),
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                modifier = Modifier.padding(start = 8.dp)
            )
            Spacer(modifier = Modifier.weight(1f))
            IconButton(
                onClick = onClose,
                modifier = Modifier.testTag("pairingScanClose")
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.onboarding_scan_close),
                    tint = Color.White
                )
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (scanFrames == null) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    shape = MaterialTheme.shapes.medium
                ) {
                    Text(
                        text = stringResource(R.string.onboarding_scan_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
            if (invalidPayload) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = MaterialTheme.shapes.medium
                ) {
                    Text(
                        text = stringResource(R.string.onboarding_scan_invalid),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .padding(16.dp)
                            .testTag("pairingScanInvalid")
                    )
                }
            }
        }
    }
}

/**
 * Camera permission gate plus the CameraX preview/analysis bound to the
 * hosting lifecycle. A denial never crashes: it shows a closed hint and a
 * button to the app settings.
 */
@Composable
private fun CameraScanner(onPayload: (String) -> Unit) {
    val context = LocalContext.current
    // CameraX needs the hosting lifecycle. It comes from the activity rather
    // than a separate lifecycle-compose dependency.
    val lifecycleOwner = LocalActivity.current as? LifecycleOwner
    var granted by remember { mutableStateOf(hasCameraPermission(context)) }
    var requested by rememberSaveable { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { result -> granted = result }

    DisposableEffect(lifecycleOwner) {
        val owner = lifecycleOwner ?: return@DisposableEffect onDispose {}
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                granted = hasCameraPermission(context)
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        if (!granted && !requested) {
            requested = true
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    if (granted && lifecycleOwner != null) {
        CameraPreview(onPayload = onPayload, lifecycleOwner = lifecycleOwner)
    } else if (granted) {
        CenteredNotice(text = stringResource(R.string.onboarding_scan_camera_unavailable))
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = stringResource(R.string.onboarding_scan_permission_denied),
                color = Color.White,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyLarge
            )
            Button(
                onClick = { openAppSettings(context) },
                modifier = Modifier.padding(top = 16.dp)
            ) {
                Text(text = stringResource(R.string.onboarding_scan_open_settings))
            }
        }
    }
}

@Composable
private fun CameraPreview(onPayload: (String) -> Unit, lifecycleOwner: LifecycleOwner) {
    val context = LocalContext.current
    val currentOnPayload by rememberUpdatedState(onPayload)
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    DisposableEffect(Unit) {
        onDispose { analysisExecutor.shutdown() }
    }

    LaunchedEffect(context) {
        cameraProvider = runCatching {
            val future = ProcessCameraProvider.getInstance(context)
            suspendCancellableCoroutine { continuation ->
                future.addListener(
                    {
                        try {
                            continuation.resume(future.get())
                        } catch (error: Throwable) {
                            continuation.resumeWithException(error)
                        }
                    },
                    ContextCompat.getMainExecutor(context)
                )
            }
        }.getOrNull()
    }

    DisposableEffect(cameraProvider, lifecycleOwner) {
        val provider = cameraProvider
        val preview = Preview.Builder().build()
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .apply {
                setAnalyzer(
                    analysisExecutor,
                    QrImageAnalyzer { payload -> currentOnPayload(payload) }
                )
            }
        if (provider != null) {
            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
                preview.setSurfaceProvider(previewView.surfaceProvider)
            } catch (_: Exception) {
                // A device without a usable back camera fails closed: the
                // scanner keeps showing the hint instead of crashing.
            }
        }
        onDispose {
            analysis.clearAnalyzer()
            provider?.unbindAll()
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = Modifier.fillMaxSize()
    )
}

@Composable
private fun CenteredNotice(text: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = Color.White,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

private fun hasCameraPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED

/**
 * Opens this app's system settings page. Like the notification settings
 * helper, it never crashes when no activity handles the intent.
 */
private fun openAppSettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null)
    )
    if (context !is Activity) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
}
