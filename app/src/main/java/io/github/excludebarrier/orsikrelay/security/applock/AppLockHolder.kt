package io.github.excludebarrier.orsikrelay.security.applock

import android.content.Context
import androidx.annotation.VisibleForTesting
import io.github.excludebarrier.orsikrelay.data.settings.AppLockSettingStore
import io.github.excludebarrier.orsikrelay.data.settings.SharedPreferencesAppLockSettingStore

/**
 * Process-scoped holder of the single [AppLockController].
 *
 * Process scope is what makes a configuration change safe: the recreated
 * activity gets the same controller, so an unlocked app does not lock merely
 * because it rotated. A real background period is still measured on the
 * controller because both activities report their stop/start to it.
 *
 * Test-injected stores bypass the cache, so each test gets an isolated
 * controller; production always uses the persisted preference store.
 */
internal object AppLockHolder {
    @Volatile
    private var cached: AppLockController? = null

    fun get(
        context: Context,
        storeOverride: AppLockSettingStore? = null
    ): AppLockController {
        if (storeOverride != null) {
            return AppLockController(
                initiallyEnabled = readEnabled(storeOverride),
                persist = { enabled -> storeOverride.write(enabled) }
            )
        }
        val existing = cached
        if (existing != null) return existing
        return synchronized(this) {
            cached ?: run {
                val store = SharedPreferencesAppLockSettingStore(context.applicationContext)
                AppLockController(
                    initiallyEnabled = readEnabled(store),
                    persist = { enabled -> store.write(enabled) }
                ).also { cached = it }
            }
        }
    }

    private fun readEnabled(store: AppLockSettingStore): Boolean =
        runCatching { store.read() }.getOrDefault(false)

    @VisibleForTesting
    internal fun resetForTest() {
        synchronized(this) { cached = null }
    }
}
