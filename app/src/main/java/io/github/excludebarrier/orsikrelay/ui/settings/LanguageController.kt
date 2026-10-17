package io.github.excludebarrier.orsikrelay.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import io.github.excludebarrier.orsikrelay.data.settings.LanguageSettingStore
import io.github.excludebarrier.orsikrelay.domain.language.SupportedLanguages

/**
 * Owns the selected app language and applies a change immediately.
 *
 * `selectedTag` is a supported tag or `null` for System. [select] normalizes
 * the value, updates the state synchronously, persists it, then applies it
 * through the platform callback (LocaleManager on API 33+, Activity
 * recreation below).
 */
@Stable
class LanguageController internal constructor(
    private val selectionState: MutableState<String?>,
    private val persist: (String?) -> Unit,
    private val applySelection: (String?) -> Unit
) {
    val selectedTag: String?
        get() = selectionState.value

    fun select(tag: String?) {
        val normalized = SupportedLanguages.normalizeTag(tag)
        selectionState.value = normalized
        runCatching { persist(normalized) }
        runCatching { applySelection(normalized) }
    }
}

/**
 * Creates a [LanguageController] seeded from [store].
 *
 * The persisted tag is read synchronously on first composition so the
 * settings UI already shows the stored choice on its first frame. A failed
 * read falls back to System.
 */
@Composable
fun rememberLanguageController(
    store: LanguageSettingStore,
    applySelection: (String?) -> Unit
): LanguageController {
    val initialTag = remember(store) {
        runCatching { store.read() }.getOrNull()
    }
    val selectionState = remember(store) { mutableStateOf(initialTag) }
    val currentApply = rememberUpdatedState(applySelection)
    return remember(store, selectionState) {
        LanguageController(
            selectionState = selectionState,
            persist = { tag -> runCatching { store.write(tag) } },
            applySelection = { tag -> currentApply.value(tag) }
        )
    }
}
