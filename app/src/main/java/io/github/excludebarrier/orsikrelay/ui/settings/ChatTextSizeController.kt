package io.github.excludebarrier.orsikrelay.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import io.github.excludebarrier.orsikrelay.data.settings.ChatTextSizeSettingStore
import io.github.excludebarrier.orsikrelay.domain.settings.ChatTextSize

/**
 * Owns the user-selected [ChatTextSize].
 *
 * [select] updates the state synchronously (so an open chat recomposes on the
 * next visit) and persists the choice. A failed persist leaves the in-memory
 * selection untouched for the current process.
 */
@Stable
class ChatTextSizeController internal constructor(
    private val sizeState: MutableState<ChatTextSize>,
    private val persist: (ChatTextSize) -> Unit
) {
    val size: ChatTextSize
        get() = sizeState.value

    fun select(size: ChatTextSize) {
        sizeState.value = size
        persist(size)
    }
}

/**
 * Creates a [ChatTextSizeController] seeded from [store].
 *
 * The persisted size is read synchronously on first composition; a failed
 * read falls back to [ChatTextSize.DEFAULT].
 */
@Composable
fun rememberChatTextSizeController(
    store: ChatTextSizeSettingStore
): ChatTextSizeController {
    val initialSize = remember(store) {
        runCatching { store.read() }.getOrDefault(ChatTextSize.DEFAULT)
    }
    val sizeState = remember(store) { mutableStateOf(initialSize) }
    return remember(store, sizeState) {
        ChatTextSizeController(sizeState) { selected ->
            runCatching { store.write(selected) }
        }
    }
}
