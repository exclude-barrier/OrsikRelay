package io.github.excludebarrier.orsikrelay.data.theme

import io.github.excludebarrier.orsikrelay.ui.theme.ThemeMode

/** Local, non-sensitive persistence boundary for the selected appearance. */
interface ThemeSettingStore {
    fun read(): ThemeMode
    fun write(mode: ThemeMode)
}
