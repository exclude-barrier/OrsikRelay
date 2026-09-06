package io.github.excludebarrier.orsikrelay.data.theme

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.excludebarrier.orsikrelay.ui.theme.ThemeMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Persistence contract for the theme selection: exactly one plain string is
 * stored (the mode name), never a secret. Absent and unknown values resolve
 * to the System default so a corrupt preference can never break startup.
 */
@RunWith(AndroidJUnit4::class)
class SharedPreferencesThemeSettingStoreTest {

    private lateinit var context: Context
    private lateinit var preferencesName: String

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        preferencesName = "hermes_theme_test_${UUID.randomUUID()}"
    }

    @After
    fun tearDown() {
        context.deleteSharedPreferences(preferencesName)
    }

    @Test
    fun absentPreferenceReadsSystemDefault() {
        val store = SharedPreferencesThemeSettingStore(context, preferencesName)

        assertEquals(ThemeMode.System, store.read())
    }

    @Test
    fun storedChoiceSurvivesARecreatedStoreInstance() {
        val store = SharedPreferencesThemeSettingStore(context, preferencesName)
        store.write(ThemeMode.Dark)

        val recreated = SharedPreferencesThemeSettingStore(context, preferencesName)

        assertEquals(ThemeMode.Dark, recreated.read())
    }

    @Test
    fun everyExplicitModeRoundTrips() {
        val store = SharedPreferencesThemeSettingStore(context, preferencesName)

        for (mode in ThemeMode.entries) {
            store.write(mode)
            assertEquals(mode, store.read())
        }
    }

    @Test
    fun storedValueIsThePlainModeNameWithoutSecrets() {
        val store = SharedPreferencesThemeSettingStore(context, preferencesName)
        store.write(ThemeMode.Light)

        val raw = context.getSharedPreferences(
            preferencesName,
            Context.MODE_PRIVATE
        ).getAll()

        assertEquals(1, raw.size)
        assertEquals("Light", raw[SharedPreferencesThemeSettingStore.PREFERENCES_KEY])
    }

    @Test
    fun unknownStoredValueFallsBackToSystemDefault() {
        context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
            .edit()
            .putString(SharedPreferencesThemeSettingStore.PREFERENCES_KEY, "Bogus")
            .commit()

        val store = SharedPreferencesThemeSettingStore(context, preferencesName)

        assertEquals(ThemeMode.System, store.read())
    }

    @Test
    fun blankPreferenceNameIsRejected() {
        val threw = try {
            SharedPreferencesThemeSettingStore(context, " ")
            false
        } catch (_: IllegalArgumentException) {
            true
        }

        org.junit.Assert.assertTrue("blank preferencesName must be rejected", threw)
    }
}
