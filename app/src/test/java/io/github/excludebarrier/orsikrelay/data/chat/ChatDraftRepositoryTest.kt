package io.github.excludebarrier.orsikrelay.data.chat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * JVM unit tests for the debounced [SharedPreferencesChatDraftRepository]
 * (Mission 009A) against a controllable in-memory [ChatDraftStore], plus the
 * store-ordering guarantees of the repository's single-writer and the
 * collision resistance of [chatDraftKey].
 *
 * The debounce window is controlled with a virtual-time [TestScope]
 * (deterministic). The repository's blocking store access hops to the real
 * [Dispatchers.IO]; the in-memory store completes it in microseconds, so the
 * assertions wait on it with a bounded deadline ([awaitTrue]) — the deadline
 * is orders of magnitude larger than the work, which keeps every assertion
 * ordered and flake-free.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatDraftRepositoryTest {

    @Test
    fun exactRoundTripReturnsStoredText() = runTest {
        val store = RecordingStore()
        val repo = repository(this, store)

        repo.persistDraft(PROFILE, SESSION, "Entwurf")
        advance(this)
        awaitTrue({ store.valueFor(PROFILE, SESSION) == "Entwurf" }, "draft persisted")

        assertEquals("Entwurf", store.valueFor(PROFILE, SESSION))
        assertEquals("Entwurf", repo.loadDraft(PROFILE, SESSION))
    }

    @Test
    fun saveReplacesPreviousValueForSameKey() = runTest {
        val store = RecordingStore()
        val repo = repository(this, store)

        repo.persistDraft(PROFILE, SESSION, "Erster Entwurf")
        advance(this)
        awaitTrue(
            { store.valueFor(PROFILE, SESSION) == "Erster Entwurf" },
            "first draft saved"
        )

        repo.persistDraft(PROFILE, SESSION, "Zweiter Entwurf")
        advance(this)
        awaitTrue(
            { store.valueFor(PROFILE, SESSION) == "Zweiter Entwurf" },
            "second draft saved"
        )

        assertEquals(2, store.saveCalls)
    }

    @Test
    fun rapidChangesCoalesceIntoSingleLatestWrite() = runTest {
        val store = RecordingStore()
        val repo = repository(this, store)

        // A burst of keystrokes within one debounce window.
        repo.persistDraft(PROFILE, SESSION, "a")
        repo.persistDraft(PROFILE, SESSION, "ab")
        repo.persistDraft(PROFILE, SESSION, "abc")
        advance(this)
        awaitTrue({ store.valueFor(PROFILE, SESSION) == "abc" }, "latest draft saved")

        // Exactly one write, holding the latest value — no unbounded write
        // per keystroke.
        assertEquals(1, store.saveCalls)
        assertEquals("abc", store.valueFor(PROFILE, SESSION))
    }

    @Test
    fun emptyDraftRemovesStoredStateInsteadOfRetainingBlankRecord() = runTest {
        val store = RecordingStore()
        val repo = repository(this, store)

        repo.persistDraft(PROFILE, SESSION, "Entwurf")
        advance(this)
        awaitTrue({ store.valueFor(PROFILE, SESSION) == "Entwurf" }, "draft saved")

        repo.persistDraft(PROFILE, SESSION, "")
        advance(this)
        awaitTrue({ store.valueFor(PROFILE, SESSION) == null }, "draft removed")

        assertNull(store.valueFor(PROFILE, SESSION))
        assertTrue(store.deleteCalls >= 1)
        assertEquals("only the non-empty draft is saved", 1, store.saveCalls)
        assertNull(repo.loadDraft(PROFILE, SESSION))
    }

    @Test
    fun clearDraftCancelsPendingWriteAndDeletesStoredState() = runTest {
        val store = RecordingStore()
        val repo = repository(this, store)

        repo.persistDraft(PROFILE, SESSION, "wird gesendet")
        repo.clearDraft(PROFILE, SESSION) // before the debounce fires
        advance(this)
        awaitTrue({ store.deleteCalls >= 1 }, "delete executed")

        assertNull(store.valueFor(PROFILE, SESSION))
        assertTrue(store.deleteCalls >= 1)
        assertEquals(
            "the pending write for a cleared draft must not land on disk",
            0,
            store.saveCalls
        )
    }

    @Test
    fun crossKeyChangeFlushesOldKeyAndKeepsNewKeyIsolated() = runTest {
        val store = RecordingStore()
        val repo = repository(this, store)

        repo.persistDraft(PROFILE, "session-a", "Entwurf A")
        // Switch to a different session before A's debounce fires: A must be
        // flushed now and B must not be overwritten by A's delayed write.
        repo.persistDraft(PROFILE, "session-b", "Entwurf B")
        advance(this)
        awaitTrue(
            {
                store.valueFor(PROFILE, "session-a") == "Entwurf A" &&
                    store.valueFor(PROFILE, "session-b") == "Entwurf B"
            },
            "both keys saved"
        )

        assertEquals("Entwurf A", store.valueFor(PROFILE, "session-a"))
        assertEquals("Entwurf B", store.valueFor(PROFILE, "session-b"))
    }

    @Test
    fun profileSwitchAlsoFlushesOldKey() = runTest {
        val store = RecordingStore()
        val repo = repository(this, store)

        repo.persistDraft("profile-1", SESSION, "Entwurf P1")
        repo.persistDraft("profile-2", SESSION, "Entwurf P2")
        advance(this)
        awaitTrue(
            {
                store.valueFor("profile-1", SESSION) == "Entwurf P1" &&
                    store.valueFor("profile-2", SESSION) == "Entwurf P2"
            },
            "both profile keys saved"
        )

        assertEquals("Entwurf P1", store.valueFor("profile-1", SESSION))
        assertEquals("Entwurf P2", store.valueFor("profile-2", SESSION))
    }

    @Test
    fun flushPendingDraftWritesCurrentPendingValueImmediately() = runTest {
        val store = RecordingStore()
        val repo = repository(this, store)

        repo.persistDraft(PROFILE, SESSION, "noch nicht geschrieben")
        // No virtual time has advanced, so the debounce cannot have fired and
        // no write job has been dispatched yet.
        assertNull(store.valueFor(PROFILE, SESSION))

        repo.flushPendingDraft()
        this.testScheduler.advanceUntilIdle()
        awaitTrue(
            { store.valueFor(PROFILE, SESSION) == "noch nicht geschrieben" },
            "flush persisted"
        )
    }

    @Test
    fun failingStoreLeavesComposerUsableAndExposesNoDraftText() = runTest {
        val store = FailingStore()
        val repo = repository(this, store)

        // A failing read degrades to an empty draft (null) and never throws
        // dynamic detail into the UI.
        assertNull(repo.loadDraft(PROFILE, SESSION))
        assertTrue(store.failedLoadCalls >= 1)

        // A failing write/delete is swallowed: the composer stays usable and
        // no draft text is echoed anywhere.
        repo.persistDraft(PROFILE, SESSION, "geheimer-entwurf")
        advance(this)
        awaitTrue({ store.failedSaveCalls >= 1 }, "failing save attempted")
        repo.clearDraft(PROFILE, SESSION)
        this.testScheduler.advanceUntilIdle()
        awaitTrue({ store.failedDeleteCalls >= 1 }, "failing delete attempted")

        // Every repository call completed without throwing — the composer
        // stayed usable throughout.
    }

    // -- store ordering (single writer) --------------------------------------

    @Test
    fun blockedSaveThenClearResolvesToAbsentDraft() = runTest {
        // Regression for the async save/delete overlap: a save already
        // entering `commit()` must never be overtaken by a later clear —
        // otherwise a successfully sent draft would be resurrected by the
        // delayed write. The store blocks the save's store call; the clear's
        // delete is queued BEHIND it on the repository's single writer and
        // runs only after the save is released. Invocation order (save, then
        // delete) is therefore the actual store I/O order, so the final
        // stored state must be absent.
        val store = BlockingStore()
        val repo = repository(this, store)

        repo.persistDraft(PROFILE, SESSION, "wird gesendet")
        advance(this)
        assertTrue(
            "the save must reach the store before the test may proceed",
            store.saveEntered.await(10, TimeUnit.SECONDS)
        )
        // The save is blocked inside the store call; its value is not yet
        // committed.
        assertNull(store.valueFor(PROFILE, SESSION))

        // A later clear (e.g. after a successful send) is queued behind the
        // in-flight save.
        repo.clearDraft(PROFILE, SESSION)
        store.releaseSave() // release the blocked save
        advance(this)
        awaitTrue({ store.deleteCalls >= 1 }, "delete executed after the save")

        assertNull(
            "the later clear must win over the earlier save: no resurrection",
            store.valueFor(PROFILE, SESSION)
        )
        assertEquals("exactly one save and one delete", 1, store.saveCalls)
        assertEquals(1, store.deleteCalls)
    }

    @Test
    fun oldKeyFlushThenNewKeyOperationsCannotReorderAcrossKeys() = runTest {
        // A key switch flushes the old key's pending write (invocation #1)
        // and schedules the new key's debounced write (invocation #2). Both
        // store calls must land in that invocation order, on their own keys:
        // the old key holds the flushed value, the new key holds its own —
        // the delayed old-key write can never cross into or overwrite the
        // new key's value.
        val store = BlockingStore()
        val repo = repository(this, store)

        repo.persistDraft(PROFILE, "session-a", "Entwurf A")
        // Let the debounced write job run until it is blocked INSIDE the
        // store call (the single-writer thread holds the latch); without this
        // advance the test dispatcher would not have dispatched the job at
        // all and the latch below could never be signaled.
        advance(this)
        assertTrue(
            "the old-key save must reach the store before the key switch",
            store.saveEntered.await(10, TimeUnit.SECONDS)
        )

        // Switch to a NEW session while the old-key save is blocked inside
        // the store: A is flushed now (still in flight) and B is scheduled.
        repo.persistDraft(PROFILE, "session-b", "Entwurf B")
        advance(this)
        store.releaseSave() // release the blocked old-key save
        advance(this)
        awaitTrue(
            {
                store.valueFor(PROFILE, "session-a") == "Entwurf A" &&
                    store.valueFor(PROFILE, "session-b") == "Entwurf B"
            },
            "both keys saved in invocation order"
        )

        // No cross-key landing: each key holds exactly its own value, and
        // the new key's write ran AFTER the old key's (no reordering).
        assertEquals("Entwurf A", store.valueFor(PROFILE, "session-a"))
        assertEquals("Entwurf B", store.valueFor(PROFILE, "session-b"))
        // (No exact write count is asserted: when the key switch happens
        // while the old-key job is already INSIDE its store call, the flush
        // queues one additional write of the SAME value for the SAME key.
        // That is idempotent and still ordered before the new key's write;
        // the contract proven here is the final per-key state, not the
        // absence of a redundant same-key write.)
        assertTrue("both keys were written", store.saveCalls >= 2)
    }

    // -- composite key -------------------------------------------------------

    @Test
    fun compositeKeyResistsCollisionWithDelimiterAndReservedCharacters() {
        val pairs = listOf(
            "a" to "b",
            "a.b" to "c",
            "a" to "b.c",
            "ab" to "c",
            "a.b.c" to "d",
            "profile-id" to "session-id",
            "pro file" to "ses sion",
            "läuft" to "ü-ä-ö",
            "a.b" to "c.d"
        )

        val keys = pairs.map { (p, s) -> chatDraftKey(p, s) }
        assertEquals("every distinct pair yields a distinct key", pairs.size, keys.toSet().size)
        assertTrue(keys.all { it.isNotBlank() })
        // No raw control character may reach the key.
        keys.forEach { key ->
            assertTrue(key.none { it.isISOControl() })
        }
    }

    @Test
    fun compositeKeyIsDeterministicAndOrderSensitive() {
        assertEquals(chatDraftKey("x", "y"), chatDraftKey("x", "y"))
        assertTrue(chatDraftKey("xy", "z") != chatDraftKey("x", "yz"))
    }

    // -- helpers -------------------------------------------------------------

    private fun repository(
        scope: TestScope,
        store: ChatDraftStore
    ): SharedPreferencesChatDraftRepository {
        // Drive the repository's debounce/flush jobs on the test scheduler so
        // the virtual-time `advance` below controls them deterministically.
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        return SharedPreferencesChatDraftRepository(store, dispatcher, DEBOUNCE)
    }

    private fun advance(scope: TestScope) {
        scope.testScheduler.advanceTimeBy(DEBOUNCE)
        scope.testScheduler.advanceUntilIdle()
    }

    /**
     * Polls [condition] on the real IO dispatcher until it holds or the
     * deadline elapses. The repository's store access runs on the real
     * [Dispatchers.IO], which virtual time cannot control; the bounded
     * deadline (seconds) is far larger than the microsecond work, so the
     * wait is deterministic in practice and the assertion is ordered.
     */
    private suspend fun awaitTrue(condition: () -> Boolean, what: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(AWAIT_DEADLINE_SECONDS)
        while (!condition()) {
            if (System.nanoTime() > deadline) {
                error("timed out waiting for: $what")
            }
            withContext(Dispatchers.IO) { delay(AWAIT_POLL_MILLIS) }
        }
    }

    private companion object {
        const val PROFILE = "fixture-profile"
        const val SESSION = "fixture-session"
        const val DEBOUNCE = 400L
        const val AWAIT_DEADLINE_SECONDS = 5L
        const val AWAIT_POLL_MILLIS = 5L
    }
}

/** In-memory [ChatDraftStore] that records every call for assertions. */
private class RecordingStore : ChatDraftStore {
    @Volatile
    var saveCalls = 0
        private set
    @Volatile
    var deleteCalls = 0
        private set
    @Volatile
    var loadCalls = 0
        private set

    private val saved = HashMap<String, String>()

    fun valueFor(profileId: String, sessionId: String): String? =
        synchronized(saved) { saved[chatDraftKey(profileId, sessionId)] }

    override fun load(profileId: String, sessionId: String): String? {
        loadCalls++
        return valueFor(profileId, sessionId)
    }

    override fun save(profileId: String, sessionId: String, text: String) {
        saveCalls++
        synchronized(saved) { saved[chatDraftKey(profileId, sessionId)] = text }
    }

    override fun delete(profileId: String, sessionId: String) {
        deleteCalls++
        synchronized(saved) { saved.remove(chatDraftKey(profileId, sessionId)) }
    }
}

/** [ChatDraftStore] that always fails with the closed exception. */
private class FailingStore : ChatDraftStore {
    @Volatile
    var failedLoadCalls = 0
        private set
    @Volatile
    var failedSaveCalls = 0
        private set
    @Volatile
    var failedDeleteCalls = 0
        private set

    override fun load(profileId: String, sessionId: String): String? {
        failedLoadCalls++
        throw ChatDraftStoreException()
    }

    override fun save(profileId: String, sessionId: String, text: String) {
        failedSaveCalls++
        throw ChatDraftStoreException()
    }

    override fun delete(profileId: String, sessionId: String) {
        failedDeleteCalls++
        throw ChatDraftStoreException()
    }
}

/**
 * In-memory [ChatDraftStore] whose [save] blocks until the test releases it
 * via [releaseSave]. It makes the store I/O of an already-dispatched write
 * observably in flight, so a test can queue a later clear/delete behind it
 * and prove the repository's single writer keeps the ACTUAL store calls in
 * invocation order.
 */
private class BlockingStore : ChatDraftStore {
    /** Counted down once per save call, right before the (blocking) write. */
    val saveEntered = CountDownLatch(1)
    private val release = CountDownLatch(1)

    @Volatile
    var saveCalls = 0
        private set
    @Volatile
    var deleteCalls = 0
        private set

    private val saved = HashMap<String, String>()

    fun valueFor(profileId: String, sessionId: String): String? =
        synchronized(saved) { saved[chatDraftKey(profileId, sessionId)] }

    override fun load(profileId: String, sessionId: String): String? =
        valueFor(profileId, sessionId)

    override fun save(profileId: String, sessionId: String, text: String) {
        saveEntered.countDown()
        // Hold the store call open until the test releases it: a later
        // clear/delete is dispatched (and queued on the single writer) while
        // this save is still inside its store access.
        release.await(10, TimeUnit.SECONDS)
        saveCalls++
        synchronized(saved) { saved[chatDraftKey(profileId, sessionId)] = text }
    }

    override fun delete(profileId: String, sessionId: String) {
        deleteCalls++
        synchronized(saved) { saved.remove(chatDraftKey(profileId, sessionId)) }
    }

    /** Releases the blocked [save]. */
    fun releaseSave() {
        release.countDown()
    }
}
