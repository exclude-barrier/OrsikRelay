package io.github.excludebarrier.orsikrelay.ui.lock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.excludebarrier.orsikrelay.R
import io.github.excludebarrier.orsikrelay.security.applock.AppAuthenticator
import io.github.excludebarrier.orsikrelay.security.applock.AppLockController

/**
 * Replaces the app content while the app lock is engaged.
 *
 * The navigation host (and with it every chat, session and approval node) is
 * not composed at all while [AppLockController.locked] is true; this gate is
 * the only place that decides between the lock screen and [content].
 */
@Composable
fun AppLockGate(
    controller: AppLockController,
    appAuthenticator: AppAuthenticator?,
    content: @Composable () -> Unit
) {
    if (controller.locked) {
        // Without a device screen lock the app lock cannot be unlocked any
        // more (the PIN fallback needs one) and would lock the user out for
        // good. It adds no protection on such a device either: removing the
        // screen lock requires the current PIN, and a phone without one is
        // open anyway. So the app lock turns itself off, with a notice.
        val deviceLockMissing = remember(controller.locked, appAuthenticator) {
            appAuthenticator != null && !runCatching { appAuthenticator.canAuthenticate() }.getOrDefault(false)
        }
        if (deviceLockMissing) {
            AppLockDeviceLockMissingScreen(onContinue = { controller.setEnabled(false) })
        } else {
            AppLockScreen(
                appAuthenticator = appAuthenticator,
                onUnlocked = controller::unlock
            )
        }
    } else {
        content()
    }
}

/**
 * The lock screen itself: app name, a closed lock message and one unlock
 * action. The authentication prompt is requested exactly once when the screen
 * appears; after a cancellation the button remains and can request it again.
 */
@Composable
fun AppLockScreen(
    appAuthenticator: AppAuthenticator?,
    onUnlocked: () -> Unit,
    modifier: Modifier = Modifier
) {
    var authenticating by remember { mutableStateOf(false) }

    fun requestUnlock() {
        if (authenticating) return
        val authenticator = appAuthenticator ?: return
        authenticating = true
        authenticator.authenticate { success ->
            authenticating = false
            if (success) onUnlocked()
        }
    }

    LaunchedEffect(Unit) { requestUnlock() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("app_lock_screen")
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center
        )
        Text(
            text = stringResource(R.string.app_lock_locked),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp)
        )
        Button(
            onClick = { requestUnlock() },
            modifier = Modifier
                .padding(top = 24.dp)
                .heightIn(min = 48.dp)
                .testTag("app_lock_unlock")
        ) {
            Text(text = stringResource(R.string.app_lock_unlock))
        }
    }
}

/**
 * Shown instead of the lock screen when the device no longer has a screen
 * lock: the app lock is switched off when the user continues.
 */
@Composable
private fun AppLockDeviceLockMissingScreen(onContinue: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag("app_lock_device_lock_missing")
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center
        )
        Text(
            text = stringResource(R.string.app_lock_device_lock_missing),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp)
        )
        Button(
            onClick = onContinue,
            modifier = Modifier
                .padding(top = 24.dp)
                .heightIn(min = 48.dp)
                .testTag("app_lock_continue")
        ) {
            Text(text = stringResource(R.string.app_lock_continue))
        }
    }
}
