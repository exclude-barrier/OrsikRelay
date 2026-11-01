package io.github.excludebarrier.orsikrelay.domain.chat

/**
 * What this app remembers about locked models, per server: the model it
 * last locked for each chat and the models it locked most recently.
 *
 * Hermes does not report a chat's current model, so "current" only means
 * "last locked from this app". Only qualified model ids (`provider::model`)
 * are kept: no credential, URL or chat content. Implementations must be
 * safe to call from any thread and never throw.
 */
interface ModelMemory {
    fun current(profileId: String, sessionId: String): String?
    fun recent(profileId: String): List<String>
    fun remember(profileId: String, sessionId: String, qualified: String)
}

/** Remembers nothing (tests and previews). */
object NoModelMemory : ModelMemory {
    override fun current(profileId: String, sessionId: String): String? = null
    override fun recent(profileId: String): List<String> = emptyList()
    override fun remember(profileId: String, sessionId: String, qualified: String) = Unit
}

/** Recent models kept per server. */
const val MODEL_MEMORY_RECENT = 5

/** Chats per server whose last locked model is kept (oldest dropped first). */
const val MODEL_MEMORY_SESSIONS = 200
