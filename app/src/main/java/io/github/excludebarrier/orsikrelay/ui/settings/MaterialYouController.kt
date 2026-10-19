package io.github.excludebarrier.orsikrelay.ui.settings

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import io.github.excludebarrier.orsikrelay.data.settings.MaterialYouSettingStore

/**
 * Owns the "colors from the wallpaper" switch.
 *
 * [setEnabled] updates the state synchronously so the theme recomposes in the
 * same frame, and persists the choice. A failed persist leaves the in-memory
 * choice untouched for the current process.
 */
@Stable
class MaterialYouController internal constructor(
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
 * Dynamic color is an Android 12 (API 31) feature. The pure check keeps the
 * visibility rule unit-testable and is shared by the settings switch.
 */
internal fun isMaterialYouAvailable(sdkInt: Int): Boolean =
    sdkInt >= Build.VERSION_CODES.S

/**
 * Creates a [MaterialYouController] seeded from [store].
 *
 * The persisted value is read synchronously on first composition; a failed
 * read falls back to off (the fixed brand palette).
 */
@Composable
fun rememberMaterialYouController(
    store: MaterialYouSettingStore
): MaterialYouController {
    val initialEnabled = remember(store) {
        runCatching { store.read() }.getOrDefault(false)
    }
    val enabledState = remember(store) { mutableStateOf(initialEnabled) }
    return remember(store, enabledState) {
        MaterialYouController(enabledState) { enabled ->
            runCatching { store.write(enabled) }
        }
    }
}
