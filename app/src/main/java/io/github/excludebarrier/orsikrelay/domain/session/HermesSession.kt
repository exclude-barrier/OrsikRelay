package io.github.excludebarrier.orsikrelay.domain.session

/**
 * One session entry from `GET /api/sessions`.
 *
 * Only non-sensitive list metadata is kept: no preview text, message
 * content, tool data, system prompts or model configuration.
 */
data class HermesSession(
    val id: String,
    val title: String?,
    val source: String?,
    val lastActiveEpochSeconds: Long?,
    val messageCount: Long?,
    val pinned: Boolean
)
