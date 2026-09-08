package io.github.excludebarrier.orsikrelay.data.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Small data seam the chat UI depends on for the ORDINARY composer draft
 * (Mission 009A).
 *
 * Only the ordinary new-message draft is covered. Run/stop/steer input,
 * recovery items, run IDs, credentials, URLs and transient errors are never
 * modelled here and can therefore never be persisted through this seam.
 *
 * Contract:
 * - [loadDraft] returns the exact stored draft for the pair (null if none).
 *   It performs blocking I/O and must be called off the main thread (the UI
 *   wraps it in `withContext(Dispatchers.IO)`).
 * - [persistDraft] records the latest draft value and writes it at most once
 *   per burst of changes (debounced latest-value). An empty [text] removes
 *   the stored state instead of retaining an empty record.
 * - [clearDraft] discards any pending write for the pair and removes the
 *   stored state (used after a successful send handoff).
 * - [flushPendingDraft] writes the currently pending value immediately
 *   (deterministic flush on leaving the screen / a key switch).
 *
 * Implementations must keep the composer usable on any storage failure and
 * must never surface draft text in an error or log.
 */
interface ChatDraftRepository {
    suspend fun loadDraft(profileId: String, sessionId: String): String?
    fun persistDraft(profileId: String, sessionId: String, text: String)
    fun clearDraft(profileId: String, sessionId: String)
    fun flushPendingDraft()
}

/**
 * [ChatDraftRepository] over a [ChatDraftStore] (Mission 009A).
 *
 * Write strategy: at most one debounced write is pending at a time. Rapid
 * changes for the same key coalesce into a single latest-value write (no
 * unbounded write per keystroke). A change for a DIFFERENT key first flushes
 * the stale pending write for the old key, so a delayed old-key write can
 * never cross into — or be lost under — the new key.
 *
 * Store ordering: every store mutation (debounced save, clear/delete,
 * flush) runs through one repository-owned [Mutex]. The main-confined
 * launcher acquires it before hopping to [Dispatchers.IO], so actual blocking
 * store calls execute in invocation order without allocating a permanent
 * executor thread. A save already entering `commit()` cannot be overtaken by
 * a later clear/delete, and a successfully sent draft cannot be resurrected
 * by a delayed write. The pending slot and its bookkeeping are confined to
 * the injected (main) dispatcher. Reads ([loadDraft]) stay on
 * [Dispatchers.IO] and are exempt from the write order — the store's own
 * `commit()` semantics guarantee read-your-writes for an already-committed
 * value.
 *
 * Every storage failure is absorbed by the closed [ChatDraftStoreException]
 * in the store; this repository swallows it so the composer stays usable and
 * no user text is exposed.
 */
class SharedPreferencesChatDraftRepository(
    private val store: ChatDraftStore,
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val debounceMillis: Long = DEFAULT_DEBOUNCE_MILLIS
) : ChatDraftRepository {
    private val writeMutex = Mutex()
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher)
    @Volatile
    private var pending: PendingWrite? = null

    override suspend fun loadDraft(profileId: String, sessionId: String): String? =
        withContext(Dispatchers.IO) {
            try {
                store.load(profileId, sessionId)
            } catch (error: CancellationException) {
                // Cancellation must propagate: it signals the effect that
                // issued this load was torn down (key switch / screen left),
                // so a stale result must never be applied.
                throw error
            } catch (_: Exception) {
                // A failed read must leave the composer usable (empty draft),
                // never throw dynamic detail into the UI.
                null
            }
        }

    override fun persistDraft(profileId: String, sessionId: String, text: String) {
        val key = chatDraftKey(profileId, sessionId)
        val stale = pending
        if (stale != null && stale.key != key) {
            // Rapid profile/session switch: persist the old key's latest
            // value now so it is neither lost nor able to land under the new
            // key later.
            pending = null
            stale.job?.cancel()
            scope.launch { saveNow(stale) }
        }
        pending?.job?.cancel()
        pending = null
        if (text.isEmpty()) {
            // An empty draft removes the stored state; do not retain a blank
            // record.
            scope.launch { deleteNow(profileId, sessionId) }
        } else {
            val write = PendingWrite(key, profileId, sessionId, text)
            write.job = scope.launch {
                delay(debounceMillis)
                // Only the latest scheduled write for this key survives; an
                // older, already-dispatched write must not overwrite it.
                if (pending?.job === write.job) saveNow(write)
            }
            pending = write
        }
    }

    override fun clearDraft(profileId: String, sessionId: String) {
        val key = chatDraftKey(profileId, sessionId)
        val current = pending
        // Cancel only this key's pending write; a pending write for a
        // different (older) key is left to be flushed on its own boundary.
        if (current != null && current.key == key) {
            current.job?.cancel()
            pending = null
        }
        scope.launch { deleteNow(profileId, sessionId) }
    }

    override fun flushPendingDraft() {
        val current = pending ?: return
        pending?.job?.cancel()
        pending = null
        scope.launch { saveNow(current) }
    }

    private suspend fun saveNow(write: PendingWrite) {
        writeMutex.withLock {
            withContext(Dispatchers.IO) {
                try {
                    store.save(write.profileId, write.sessionId, write.text)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // Swallowed: a write failure leaves the composer usable and
                    // exposes no draft text.
                }
            }
        }
    }

    private suspend fun deleteNow(profileId: String, sessionId: String) {
        writeMutex.withLock {
            withContext(Dispatchers.IO) {
                try {
                    store.delete(profileId, sessionId)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // Swallowed, same reason as [saveNow].
                }
            }
        }
    }

    private class PendingWrite(
        val key: String,
        val profileId: String,
        val sessionId: String,
        val text: String
    ) {
        var job: Job? = null
    }

    companion object {
        /**
         * Default debounce window. Kept small so an in-progress draft is
         * written promptly, while still coalescing a burst of keystrokes into
         * a single write.
         */
        internal const val DEFAULT_DEBOUNCE_MILLIS = 400L
    }
}
