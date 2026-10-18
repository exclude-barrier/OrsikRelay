package io.github.excludebarrier.orsikrelay.ui.settings

import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import io.github.excludebarrier.orsikrelay.data.settings.ScreenProtectionSettingStore

/**
 * Owns the screen-protection switch.
 *
 * [setEnabled] updates the state synchronously and persists it. The window
 * flag itself is applied by [ApplyScreenProtection], so the switch reacts
 * immediately without recreating the activity.
 */
@Stable
class ScreenProtectionController internal constructor(
    private val enabledState: MutableState<Boolean>,
    private val persist: (Boolean) -> Unit
) {
    val enabled: Boolean
        get() = enabledState.value

    fun setEnabled(enabled: Boolean) {
        enabledState.value = enabled
        persist(enabled)
    }
}

/**
 * Creates a [ScreenProtectionController] seeded from [store].
 *
 * The persisted value is read synchronously on first composition; a failed
 * read falls back to off.
 */
@Composable
fun rememberScreenProtectionController(
    store: ScreenProtectionSettingStore
): ScreenProtectionController {
    val initialEnabled = remember(store) {
        runCatching { store.read() }.getOrDefault(false)
    }
    val enabledState = remember(store) { mutableStateOf(initialEnabled) }
    return remember(store, enabledState) {
        ScreenProtectionController(enabledState) { enabled ->
            runCatching { store.write(enabled) }
        }
    }
}

/**
 * Applies the controller state to the hosting activity window as
 * [WindowManager.LayoutParams.FLAG_SECURE] and keeps it in sync while the
 * activity is composed. Compose dialogs and popups inherit the flag from
 * their parent view's window by default, so no per-dialog handling is
 * required.
 */
@Composable
fun ApplyScreenProtection(controller: ScreenProtectionController) {
    val activity = LocalActivity.current
    val enabled = controller.enabled
    LaunchedEffect(activity, enabled) {
        val window = activity?.window ?: return@LaunchedEffect
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}
