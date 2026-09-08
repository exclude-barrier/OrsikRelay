package io.github.excludebarrier.orsikrelay.draft

import io.github.excludebarrier.orsikrelay.data.chat.ChatDraftRepository
import io.github.excludebarrier.orsikrelay.data.chat.chatDraftKey
import io.github.excludebarrier.orsikrelay.data.chat.encodeKeyComponent
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred

/**
 * In-memory [ChatDraftRepository] for Compose tests (Mission 009A).
 *
 * Writes synchronously (no debounce) so assertions are deterministic. Only
 * the ORDINARY draft is modelled; it can never hold run/stop/steer input,
 * recovery items, run IDs or any transient turn state. [storedDrafts] keeps
 * the live map so a test can read back what the screen asked to remember.
 */
class InMemoryChatDraftRepository : ChatDraftRepository {
    private val map = ConcurrentHashMap<String, String>()
    val storedDrafts: Map<String, String> get() = map.toMap()
    var lastPersistedText: String? = null
        private set

    override suspend fun loadDraft(profileId: String, sessionId: String): String? =
        map[chatDraftKey(profileId, sessionId)]

    override fun persistDraft(profileId: String, sessionId: String, text: String) {
        val key = chatDraftKey(profileId, sessionId)
        lastPersistedText = text
        if (text.isEmpty()) {
            map.remove(key)
        } else {
            map[key] = text
        }
    }

    override fun clearDraft(profileId: String, sessionId: String) {
        map.remove(chatDraftKey(profileId, sessionId))
        lastPersistedText = null
    }

    override fun clearProfile(profileId: String) {
        // Removes every draft of [profileId] (all sessions), exactly like the
        // production repository after a profile deletion (Mission 009B).
        val prefix = encodeKeyComponent(profileId) + "."
        map.keys.filterTo(mutableListOf()) { it.startsWith(prefix) }
            .forEach { map.remove(it) }
    }

    override fun flushPendingDraft() {
        // Synchronous store: nothing is pending.
    }

    /** Test helper: directly seed a stored draft (as if a previous session saved it). */
    fun seed(profileId: String, sessionId: String, text: String) {
        map[chatDraftKey(profileId, sessionId)] = text
    }

    /** Test helper: read back the stored draft for a pair. */
    fun stored(profileId: String, sessionId: String): String? =
        map[chatDraftKey(profileId, sessionId)]
}

/**
 * A [ChatDraftRepository] whose [loadDraft] suspends on a per-key
 * [CompletableDeferred] (Mission 009A). A test holds the deferred open so a
 * load for an OLD key stays in flight across a profile/session switch, then
 * releases it to prove the stale result can never overwrite the new screen's
 * composer. Because the suspension is a real cancellable suspension point,
 * a switched-away (cancelled) load observes cancellation instead of applying
 * its value. Writes are no-ops; only loads are gated.
 */
class ControllableDraftRepository : ChatDraftRepository {
    private val pendingLoads = ConcurrentHashMap<String, CompletableDeferred<String?>>()
    private val releasedValues = ConcurrentHashMap<String, ReleasedValue>()

    /** Holder so a nullable release can be kept in a null-free map. */
    private class ReleasedValue(val value: String?)

    override suspend fun loadDraft(profileId: String, sessionId: String): String? {
        val key = chatDraftKey(profileId, sessionId)
        val deferred = pendingLoads.getOrPut(key) { CompletableDeferred() }
        // Cancellable suspension: if the screen's keyed effect is disposed
        // (key switch) while this load is in flight, the await is cancelled
        // and the value is never returned to a switched-away screen.
        try {
            return deferred.await()
        } catch (error: CancellationException) {
            throw error
        }
    }

    override fun persistDraft(profileId: String, sessionId: String, text: String) {
        // no-op for the stale-load test
    }

    private val clearedKeysSnapshot = ConcurrentHashMap.newKeySet<String>()

    override fun clearDraft(profileId: String, sessionId: String) {
        // Recorded so a test can prove the screen cleared the stored state.
        clearedKeysSnapshot.add(chatDraftKey(profileId, sessionId))
    }

    /** Test helper: true if the screen asked to clear the stored state for this pair. */
    fun wasCleared(profileId: String, sessionId: String): Boolean =
        clearedKeysSnapshot.contains(chatDraftKey(profileId, sessionId))

    override fun clearProfile(profileId: String) {
        // no-op: the stale-load test never deletes a profile
    }

    override fun flushPendingDraft() {
        // no-op
    }

    /** Test helper: true once a load for [profileId]/[sessionId] has started. */
    fun loadStarted(profileId: String, sessionId: String): Boolean =
        pendingLoads.containsKey(chatDraftKey(profileId, sessionId))

    /** Test helper: release a held load for [profileId]/[sessionId] with [value]. */
    fun release(profileId: String, sessionId: String, value: String?) {
        val key = chatDraftKey(profileId, sessionId)
        releasedValues[key] = ReleasedValue(value)
        pendingLoads[key]?.complete(value)
    }

    /** Test helper: the value a completed load for the pair holds, else null. */
    fun storedValue(profileId: String, sessionId: String): String? =
        releasedValues[chatDraftKey(profileId, sessionId)]?.value
}

/**
 * No-op [ChatDraftRepository] (Mission 009A) used by pre-existing
 * ChatScreen tests that exercise the history/turn/stop/steer slices and do
 * not concern themselves with draft persistence. It keeps the composer
 * usable and persists nothing.
 */
class NoOpChatDraftRepository : ChatDraftRepository {
    override suspend fun loadDraft(profileId: String, sessionId: String): String? = null
    override fun persistDraft(profileId: String, sessionId: String, text: String) {}
    override fun clearDraft(profileId: String, sessionId: String) {}
    override fun clearProfile(profileId: String) {}
    override fun flushPendingDraft() {}
}
