package io.github.excludebarrier.orsikrelay.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import io.github.excludebarrier.orsikrelay.data.theme.ThemeSettingStore

/**
 * Resolves the user-selected [ThemeMode] and applies it immediately.
 *
 * The controller owns a single [MutableState] of the current mode plus a
 * [persist] callback. [select] updates the state synchronously (so the
 * change is visible in the same composition) and persists it in call
 * order.
 *
 * [rememberThemeModeController] reads the persisted mode synchronously on
 * first composition (a single SharedPreferences.getString) so the very
 * first frame is already the stored mode — no visible light/dark flash.
 * Reads and writes both fall back to [ThemeMode.DEFAULT] on any I/O error.
 */
@Stable
class ThemeModeController internal constructor(
    private val modeState: MutableState<ThemeMode>,
    private val persist: (ThemeMode) -> Unit
) {
    var mode: ThemeMode
        get() = modeState.value
        private set(value) {
            modeState.value = value
        }

    fun select(mode: ThemeMode) {
        this.mode = mode
        persist(mode)
    }
}

/**
 * Creates a [ThemeModeController] seeded from [store]. The persisted mode
 * is read once, synchronously, on first composition so the initial frame
 * is already correct; a failed read falls back to [ThemeMode.DEFAULT].
 */
@Composable
fun rememberThemeModeController(store: ThemeSettingStore): ThemeModeController {
    val initialMode = remember(store) {
        runCatching { store.read() }.getOrDefault(ThemeMode.DEFAULT)
    }
    val modeState = remember(store) { mutableStateOf(initialMode) }
    return remember(store, modeState) {
        ThemeModeController(modeState) { selected ->
            runCatching { store.write(selected) }
        }
    }
}
