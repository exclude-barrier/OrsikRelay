package io.github.excludebarrier.orsikrelay.data.chat

import android.annotation.SuppressLint
import android.content.Context
import io.github.excludebarrier.orsikrelay.domain.chat.MODEL_MEMORY_RECENT
import io.github.excludebarrier.orsikrelay.domain.chat.MODEL_MEMORY_SESSIONS
import io.github.excludebarrier.orsikrelay.domain.chat.ModelMemory
import io.github.excludebarrier.orsikrelay.domain.chat.SessionModelOption

/** [ModelMemory] in app-private preferences; the logic is [PreferenceModelMemory]. */
@SuppressLint("UseKtx")
class SharedPreferencesModelMemory(
    context: Context,
    preferencesName: String = PREFERENCES_NAME
) : ModelMemory {

    private val preferences = context.applicationContext.getSharedPreferences(
        preferencesName.also { require(it.isNotBlank()) },
        Context.MODE_PRIVATE
    )

    private val delegate = PreferenceModelMemory(
        read = { key ->
            try {
                preferences.getString(key, null)
            } catch (_: ClassCastException) {
                null
            }
        },
        write = { values ->
            val editor = preferences.edit()
            values.forEach { (key, value) -> editor.putString(key, value) }
            editor.apply()
        }
    )

    override fun current(profileId: String, sessionId: String): String? = delegate.current(profileId, sessionId)
    override fun recent(profileId: String): List<String> = delegate.recent(profileId)
    override fun remember(profileId: String, sessionId: String, qualified: String) =
        delegate.remember(profileId, sessionId, qualified)

    companion object {
        internal const val PREFERENCES_NAME = "orsik_relay_model_memory"
    }
}

/**
 * Per server a newline list of recent qualified ids and a newline list of
 * `sessionId<TAB>qualified`, both newest first and bounded. Values that no
 * longer parse are ignored.
 */
internal class PreferenceModelMemory(
    private val read: (String) -> String?,
    private val write: (Map<String, String>) -> Unit
) : ModelMemory {

    override fun current(profileId: String, sessionId: String): String? =
        sessions(profileId).firstOrNull { it.first == sessionId }?.second

    override fun recent(profileId: String): List<String> = lines(RECENT_KEY + profileId).filter(::isQualified)

    @Synchronized
    override fun remember(profileId: String, sessionId: String, qualified: String) {
        if (!isQualified(qualified) || sessionId.isBlank() || sessionId.any { it == '\t' || it == '\n' }) return
        val recent = (listOf(qualified) + recent(profileId).filter { it != qualified }).take(MODEL_MEMORY_RECENT)
        val sessions = (listOf(sessionId to qualified) + sessions(profileId).filter { it.first != sessionId })
            .take(MODEL_MEMORY_SESSIONS)
        write(
            mapOf(
                RECENT_KEY + profileId to recent.joinToString("\n"),
                SESSIONS_KEY + profileId to sessions.joinToString("\n") { "${it.first}\t${it.second}" }
            )
        )
    }

    private fun sessions(profileId: String): List<Pair<String, String>> =
        lines(SESSIONS_KEY + profileId).mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size == 2 && parts[0].isNotBlank() && isQualified(parts[1])) parts[0] to parts[1] else null
        }

    private fun lines(key: String): List<String> = read(key)?.split('\n')?.filter { it.isNotBlank() }.orEmpty()

    private fun isQualified(value: String): Boolean = value.split("::", limit = 2).let {
        it.size == 2 && SessionModelOption(it[0], it[1]).isValid()
    }

    private companion object {
        const val RECENT_KEY = "recent_v1:"
        const val SESSIONS_KEY = "sessions_v1:"
    }
}
