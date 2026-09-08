package io.github.excludebarrier.orsikrelay.data.chat

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
 * Instrumentation tests for the app-private [SharedPreferencesChatDraftStore]
 * (Mission 009A) against the real production persistence implementation
 * (SharedPreferences). Covers exact round-trip, replacement, empty-delete,
 * composite-key collision resistance and the closed failure surface (no
 * input echo).
 */
@RunWith(AndroidJUnit4::class)
class SharedPreferencesChatDraftStoreTest {

    private lateinit var context: Context
    private lateinit var preferencesName: String
    private lateinit var store: SharedPreferencesChatDraftStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        preferencesName = "hermes_chat_draft_test_${UUID.randomUUID()}"
        store = SharedPreferencesChatDraftStore(context, preferencesName)
    }

    @After
    fun tearDown() {
        context.deleteSharedPreferences(preferencesName)
    }

    @Test
    fun exactDraftSurvivesARecreatedStoreInstance() {
        val draft = "Entwurf mit\nZeilenumbruch\tund Sonderzeichen üöä"
        store.save(PROFILE, SESSION, draft)

        // A recreated instance (as after an Activity/config recreation) reads
        // back the exact text from the same app-private preferences.
        val recreated = SharedPreferencesChatDraftStore(context, preferencesName)

        assertEquals(draft, recreated.load(PROFILE, SESSION))
    }

    @Test
    fun unknownPairReturnsNull() {
        assertNull(store.load("other-profile", "other-session"))
        assertNull(store.load(PROFILE, SESSION))
    }

    @Test
    fun saveReplacesPreviousValueForSamePair() {
        store.save(PROFILE, SESSION, "Erster Entwurf")
        store.save(PROFILE, SESSION, "Zweiter Entwurf")

        assertEquals("Zweiter Entwurf", store.load(PROFILE, SESSION))
    }

    @Test
    fun deleteRemovesStoredDraftAndIsIdempotent() {
        store.save(PROFILE, SESSION, "Entwurf")

        store.delete(PROFILE, SESSION)
        store.delete(PROFILE, SESSION) // idempotent

        val recreated = SharedPreferencesChatDraftStore(context, preferencesName)
        assertNull(recreated.load(PROFILE, SESSION))
    }

    @Test
    fun noBlankRecordIsRetainedAfterDeletingOnlyPair() {
        store.save(PROFILE, SESSION, "Entwurf")
        store.delete(PROFILE, SESSION)

        // The raw preferences must not retain an empty string under the key.
        val raw = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        val keys = raw.all.keys.filter { it.startsWith(KEY_PREFIX) }
        assertTrue("no draft key may remain after delete", keys.isEmpty())
    }

    @Test
    fun compositeKeyKeepsDelimiterAndReservedCharacterIdsIsolated() {
        // Pairs that would collide under a naive "profile.session" key must
        // stay fully isolated.
        store.save("a", "b.c", "value A")
        store.save("a.b", "c", "value B")
        store.save("ab", "c", "value AB")
        store.save(PROFILE, "session-with.dot", "value dot")
        store.save("läuft", "ü-ä-ö", "value unicode")

        assertEquals("value A", store.load("a", "b.c"))
        assertEquals("value B", store.load("a.b", "c"))
        assertEquals("value AB", store.load("ab", "c"))
        assertEquals("value dot", store.load(PROFILE, "session-with.dot"))
        assertEquals("value unicode", store.load("läuft", "ü-ä-ö"))
    }

    @Test
    fun blankPreferencesNameFailsClosedWithoutInputEcho() {
        // An invalid (blank) preferences name must fail with the single,
        // closed exception and must not echo any input (IDs or draft text).
        val error = assertDraftFailure { SharedPreferencesChatDraftStore(context, "   ") }

        assertClosed(error, listOf(PROFILE, SESSION, "secret-draft-text"))
    }

    @Test
    fun emptyPreferencesNameFailsClosedWithoutInputEcho() {
        val error = assertDraftFailure {
            SharedPreferencesChatDraftStore(context, "").load(PROFILE, SESSION)
        }

        assertClosed(error, listOf(PROFILE, SESSION, "seed-value"))
    }

    // -- helpers -------------------------------------------------------------

    private fun assertDraftFailure(action: () -> Unit): ChatDraftStoreException {
        try {
            action()
        } catch (error: ChatDraftStoreException) {
            return error
        } catch (_: Throwable) {
            fail("Expected ChatDraftStoreException")
        }
        fail("Expected ChatDraftStoreException")
        throw AssertionError("unreachable")
    }

    private fun assertClosed(error: ChatDraftStoreException, forbidden: List<String>) {
        assertEquals(ChatDraftStoreException.MESSAGE, error.message)
        forbidden.forEach { value ->
            assertFalse(
                "the closed exception must not echo input: $value",
                error.toString().contains(value)
            )
        }
    }

    private companion object {
        const val PROFILE = "fixture-profile"
        const val SESSION = "fixture-session"
        const val KEY_PREFIX = "chat.draft.v1."
    }
}
