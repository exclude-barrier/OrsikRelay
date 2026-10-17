package io.github.excludebarrier.orsikrelay.data.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Persistence contract for the language selection: only a supported language
 * tag is stored, and System is the safe default for absent, unknown or
 * unreadable values.
 */
@RunWith(AndroidJUnit4::class)
class SharedPreferencesLanguageSettingStoreTest {

    private lateinit var context: Context
    private lateinit var preferencesName: String

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        preferencesName = "orsik_language_test_${UUID.randomUUID()}"
    }

    @After
    fun tearDown() {
        context.deleteSharedPreferences(preferencesName)
    }

    @Test
    fun absentPreferenceReadsSystem() {
        val store = SharedPreferencesLanguageSettingStore(context, preferencesName)

        assertNull(store.read())
    }

    @Test
    fun storedLanguageSurvivesARecreatedStoreInstance() {
        val store = SharedPreferencesLanguageSettingStore(context, preferencesName)
        store.write("de")

        val recreated = SharedPreferencesLanguageSettingStore(context, preferencesName)

        assertEquals("de", recreated.read())
    }

    @Test
    fun writingSystemRemovesTheStoredChoice() {
        val store = SharedPreferencesLanguageSettingStore(context, preferencesName)
        store.write("de")
        store.write(null)

        assertNull(store.read())
        val raw = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE).all
        assertTrue(raw.isEmpty())
    }

    @Test
    fun unknownStoredValueFallsBackToSystem() {
        context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
            .edit()
            .putString(SharedPreferencesLanguageSettingStore.PREFERENCES_KEY, "fr")
            .commit()

        val store = SharedPreferencesLanguageSettingStore(context, preferencesName)

        assertNull(store.read())
    }

    @Test
    fun unreadableStoredValueFallsBackToSystem() {
        context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
            .edit()
            .putInt(SharedPreferencesLanguageSettingStore.PREFERENCES_KEY, 7)
            .commit()

        val store = SharedPreferencesLanguageSettingStore(context, preferencesName)

        assertNull(store.read())
    }

    @Test
    fun unsupportedWriteIsStoredAsSystem() {
        val store = SharedPreferencesLanguageSettingStore(context, preferencesName)
        store.write("fr")

        assertNull(store.read())
    }

    @Test
    fun storedValueIsThePlainLanguageTagWithoutSecrets() {
        val store = SharedPreferencesLanguageSettingStore(context, preferencesName)
        store.write("de")

        val raw = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE).all

        assertEquals(1, raw.size)
        assertEquals("de", raw[SharedPreferencesLanguageSettingStore.PREFERENCES_KEY])
    }

    @Test
    fun blankPreferenceNameIsRejected() {
        val threw = try {
            SharedPreferencesLanguageSettingStore(context, " ")
            false
        } catch (_: IllegalArgumentException) {
            true
        }

        assertTrue("blank preferencesName must be rejected", threw)
    }
}
