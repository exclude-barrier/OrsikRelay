package io.github.excludebarrier.orsikrelay.data.profile

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The app-private active server selection store on a real
 * [SharedPreferences] (connected).
 *
 * The store persists exactly one plain profile ID — never an API key,
 * URL, draft or chat data. A recreated instance sees the same value
 * (cross-instance / app-restart durability), every failure maps to the
 * closed [ActiveServerSelectionStoreException] and never echoes input.
 */
@RunWith(AndroidJUnit4::class)
class SharedPreferencesActiveServerSelectionStoreTest {

    private lateinit var context: Context
    private lateinit var preferencesName: String
    private lateinit var store: SharedPreferencesActiveServerSelectionStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        preferencesName = "hermes_selection_test_${UUID.randomUUID()}"
        store = SharedPreferencesActiveServerSelectionStore(context, preferencesName)
    }

    @After
    fun tearDown() {
        context.deleteSharedPreferences(preferencesName)
    }

    @Test
    fun selectionSurvivesARecreatedStoreInstance() {
        store.write(FIRST_ID)

        val recreated = SharedPreferencesActiveServerSelectionStore(context, preferencesName)

        assertEquals(FIRST_ID, recreated.read())
        assertEquals(FIRST_ID, store.read())
    }

    @Test
    fun writeReplacesThePreviousSelection() {
        store.write(FIRST_ID)

        store.write(SECOND_ID)

        assertEquals(SECOND_ID, store.read())
        // The preferences hold exactly one record: the current selection.
        val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        assertEquals(1, preferences.all.size)
        assertEquals(SECOND_ID, preferences.all[SharedPreferencesActiveServerSelectionStore.SELECTION_KEY])
    }

    @Test
    fun clearIsPersistentAndIdempotent() {
        store.write(FIRST_ID)

        store.clear()
        store.clear()

        assertNull(store.read())
        assertNull(SharedPreferencesActiveServerSelectionStore(context, preferencesName).read())
    }

    @Test
    fun emptyStoreReadsNull() {
        assertNull(store.read())
    }

    @Test
    fun blankProfileIdIsRejectedGenerically() {
        val blank = "   "
        val error = assertSelectionFailure { store.write(blank) }
        assertEquals(ActiveServerSelectionStoreException.MESSAGE, error.message)
        assertFalse(error.toString().contains(blank))
        assertNull(store.read())
    }

    @Test
    fun preferencesNeverContainMoreThanTheSelection() {
        store.write(FIRST_ID)

        val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        assertTrue(
            "The selection store must not contain the credential: ${preferences.all.keys}",
            preferences.all.values.none { it.toString().contains(SYNTHETIC_SECRET) }
        )
        assertTrue(
            "The selection store must not contain a URL: ${preferences.all.keys}",
            preferences.all.values.none { it.toString().contains(BASE_URL) }
        )
    }

    private fun assertSelectionFailure(
        action: () -> Unit
    ): ActiveServerSelectionStoreException {
        return try {
            action()
            fail("Expected ActiveServerSelectionStoreException")
            throw AssertionError("unreachable")
        } catch (error: ActiveServerSelectionStoreException) {
            error
        } catch (_: Throwable) {
            fail("Expected ActiveServerSelectionStoreException")
            throw AssertionError("unreachable")
        }
    }

    private companion object {
        const val FIRST_ID = "fixture-profile-first"
        const val SECOND_ID = "fixture-profile-second"
        const val SYNTHETIC_SECRET = "fixture-credential-012"
        const val BASE_URL = "https://example.invalid/p/test"
    }
}
