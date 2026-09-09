package io.github.excludebarrier.orsikrelay.data.profile

/**
 * Synchronous, app-private storage seam for the active server selection
 * (Mission 012).
 *
 * The selection is private local metadata: exactly ONE non-secret profile
 * ID. It never stores an API key, a URL, a draft or any chat/session data.
 *
 * - [write] persists [profileId], replacing any previous selection. A
 *   blank ID is rejected.
 * - [clear] removes the selection; it is idempotent.
 * - [read] returns the stored ID or null when nothing is stored.
 *
 * Implementations must commit synchronously (the caller runs off the main
 * thread) and must fail with the closed, input-free
 * [ActiveServerSelectionStoreException].
 */
interface ActiveServerSelectionStore {
    fun read(): String?
    fun write(profileId: String)
    fun clear()
}
