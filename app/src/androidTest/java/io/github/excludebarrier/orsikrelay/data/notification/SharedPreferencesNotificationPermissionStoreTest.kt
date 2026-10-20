package io.github.excludebarrier.orsikrelay.data.notification

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumentation tests for [SharedPreferencesNotificationPermissionStore].
 *
 * The store's only side effect is the "asked at most once" flag; it must be
 * written asynchronously (`apply`) because [markRequested] runs in the
 * notification-permission launcher callback on the main thread.
 */
@RunWith(AndroidJUnit4::class)
class SharedPreferencesNotificationPermissionStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferencesName = "notification-permission-test"

    @After
    fun tearDown() {
        context.deleteSharedPreferences(preferencesName)
    }

    @Test
    fun markRequestedUsesApplyAndPersistsTheFlag() {
        val preferences = CountingPreferences(
            context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        )
        val store = SharedPreferencesNotificationPermissionStore(
            context = wrapper(preferences),
            preferencesName = preferencesName
        )

        assertFalse(store.hasRequested)
        store.markRequested()

        assertTrue(store.hasRequested)
        assertEquals("markRequested must not block on a disk commit", 0, preferences.commitCalls)
        assertEquals(1, preferences.applyCalls)
        assertTrue(
            "the flag survives a new store instance",
            SharedPreferencesNotificationPermissionStore(
                context = wrapper(preferences),
                preferencesName = preferencesName
            ).hasRequested
        )
    }

    /**
     * Redirects the store's `applicationContext.getSharedPreferences(...)`
     * lookup to [preferences] so the editor call can be observed.
     */
    private fun wrapper(preferences: SharedPreferences): Context =
        object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                preferences
        }

    private class CountingPreferences(
        private val delegate: SharedPreferences
    ) : SharedPreferences by delegate {
        var applyCalls = 0
        var commitCalls = 0

        override fun edit(): SharedPreferences.Editor = CountingEditor(delegate.edit())

        private inner class CountingEditor(
            private val editor: SharedPreferences.Editor
        ) : SharedPreferences.Editor by editor {
            // Keep the fluent builder on this wrapper so a chained apply()/
            // commit() cannot bypass the counters through the delegate.
            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
                editor.putBoolean(key, value)
                return this
            }

            override fun apply() {
                applyCalls += 1
                editor.apply()
            }

            override fun commit(): Boolean {
                commitCalls += 1
                return editor.commit()
            }
        }
    }
}
