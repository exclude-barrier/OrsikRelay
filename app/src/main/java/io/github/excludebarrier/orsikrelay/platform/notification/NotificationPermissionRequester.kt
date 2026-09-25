package io.github.excludebarrier.orsikrelay.platform.notification

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import io.github.excludebarrier.orsikrelay.R
import io.github.excludebarrier.orsikrelay.data.notification.NotificationPermissionStore

/**
 * Contextual request for the Android 13+ POST_NOTIFICATIONS permission (P3).
 *
 * It fires only when all of the following hold: a run is currently active (the
 * user is about to receive a local notification), the app is in the foreground
 * (a permission dialog can only be shown while visible), the permission has not
 * been requested yet, and it is not already granted. A short in-app
 * justification precedes the system dialog. The user's choice — allow, deny, or
 * dismiss the justification — is recorded via the store so the request is never
 * repeated (no spamming) and a denial is respected (the app stays fully
 * functional without notifications).
 *
 * On Android 13 and below the runtime permission does not exist, so this is a
 * no-op and the app posts notifications through the legacy app-level setting.
 */
@Composable
fun NotificationPermissionRequester(
    store: NotificationPermissionStore,
    isRunActive: Boolean
) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // The choice (allow or deny) is final for the app's lifetime: record it
        // so the request is never repeated.
        store.markRequested()
    }
    var justificationVisible by remember { mutableStateOf(false) }

    LaunchedEffect(isRunActive) {
        if (!isRunActive) return@LaunchedEffect
        if (store.hasRequested) return@LaunchedEffect
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) return@LaunchedEffect
        justificationVisible = true
    }

    if (justificationVisible) {
        AlertDialog(
            onDismissRequest = {
                // Dismissing the justification is treated as "not now": the
                // choice is recorded so the app never re-asks.
                justificationVisible = false
                store.markRequested()
            },
            title = { Text(stringResource(R.string.notification_permission_title)) },
            text = { Text(stringResource(R.string.notification_permission_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        justificationVisible = false
                        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                ) {
                    Text(stringResource(R.string.notification_permission_allow))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        justificationVisible = false
                        store.markRequested()
                    }
                ) {
                    Text(stringResource(R.string.notification_permission_not_now))
                }
            }
        )
    }
}
