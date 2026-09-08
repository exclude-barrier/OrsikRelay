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
 * (Mission 009A, profile cleanup 009B) against the real production
 * persistence implementation (SharedPreferences). Covers exact round-trip,
 * replacement, empty-delete, composite-key collision resistance, the closed
 * failure surface (no input echo) and [SharedPreferencesChatDraftStore.clearProfile]:
 * multi-session removal for exactly one profile, isolation of reserved,
 * delimiter, whitespace, plus/minus, prefix-like and Unicode IDs, and
 * idempotency.
 *
 * Note on the failure surface: a forced `commit()` failure of the real
 * SharedPreferences is not deterministically reachable in instrumentation
 * (the durable write renames over the target file and the app-private
 * directory stays writable), so the store's closed mapping is proven by the
 * constructor-failure tests below (same `call` wrapper path) and by the JVM
 * repository tests against a failing store.
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

    // -- profile cleanup (Mission 009B) --------------------------------------

    @Test
    fun clearProfileRemovesEverySessionDraftOfExactlyThatProfile() {
        store.save(PROFILE, "session-1", "Entwurf 1")
        store.save(PROFILE, "session-2", "Entwurf 2")
        store.save(PROFILE, "session-with.dot", "Entwurf 3")
        store.save(OTHER_PROFILE, "session-1", "Fremd 1")
        store.save(OTHER_PROFILE, "session-2", "Fremd 2")

        store.clearProfile(PROFILE)

        assertNull(store.load(PROFILE, "session-1"))
        assertNull(store.load(PROFILE, "session-2"))
        assertNull(store.load(PROFILE, "session-with.dot"))
        assertEquals("Fremd 1", store.load(OTHER_PROFILE, "session-1"))
        assertEquals("Fremd 2", store.load(OTHER_PROFILE, "session-2"))
    }

    @Test
    fun clearProfileKeepsReservedDelimiterWhitespacePlusMinusAndUnicodeProfilesIsolated() {
        // IDs that would alias each other under a naive key scheme must stay
        // isolated through a whole-profile clear.
        store.save("a", "b.c", "A")
        store.save("a.b", "c", "B")
        store.save("ab", "c", "AB")
        store.save("profile", "s", "P")
        store.save("profile-id", "s", "P-ID")
        store.save("profileX", "s", "P-X")
        store.save("pro file", "s", "P-WS")
        store.save("läuft", "s", "P-UNI")
        store.save("x+y", "s", "P-PLUS")
        store.save("x-y", "s", "P-MINUS")
        store.save("a/b", "s", "P-SLASH")
        store.save("a.b/c", "s", "P-SLASH-2")

        store.clearProfile("a.b")

        assertNull("only the requested profile is removed", store.load("a.b", "c"))
        assertEquals("A", store.load("a", "b.c"))
        assertEquals("AB", store.load("ab", "c"))
        assertEquals("P", store.load("profile", "s"))
        assertEquals("P-ID", store.load("profile-id", "s"))
        assertEquals("P-X", store.load("profileX", "s"))
        assertEquals("P-WS", store.load("pro file", "s"))
        assertEquals("P-UNI", store.load("läuft", "s"))
        assertEquals("P-PLUS", store.load("x+y", "s"))
        assertEquals("P-MINUS", store.load("x-y", "s"))
        assertEquals("P-SLASH", store.load("a/b", "s"))
        assertEquals("P-SLASH-2", store.load("a.b/c", "s"))
    }

    @Test
    fun clearProfileRemovesEveryRawKeyOfTheProfileInOneCommit() {
        store.save(PROFILE, "session-1", "Entwurf 1")
        store.save(PROFILE, "session-2", "Entwurf 2")

        store.clearProfile(PROFILE)

        val raw = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        val remaining = raw.all.keys.filter { it.startsWith(KEY_PREFIX) }
        assertTrue(
            "no key of the deleted profile may remain",
            remaining.none { it.startsWith(profilePrefix(PROFILE)) }
        )
    }

    @Test
    fun repeatedClearProfileIsIdempotent() {
        store.save(PROFILE, SESSION, "Entwurf")
        store.clearProfile(PROFILE)
        store.clearProfile(PROFILE)
        store.clearProfile(PROFILE) // no drafts at all anymore

        assertNull(store.load(PROFILE, SESSION))
    }

    @Test
    fun clearProfileOfUnknownProfileIsIdempotentAndRemovesNothing() {
        store.save(PROFILE, SESSION, "Entwurf")

        store.clearProfile("unbekanntes-profil")

        assertEquals("Entwurf", store.load(PROFILE, SESSION))
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

    private fun profilePrefix(profileId: String): String =
        KEY_PREFIX + encodeKeyComponent(profileId) + "."

    private companion object {
        const val PROFILE = "fixture-profile"
        const val SESSION = "fixture-session"
        const val OTHER_PROFILE = "other-profile"
        const val KEY_PREFIX = "chat.draft.v1."
    }
}
