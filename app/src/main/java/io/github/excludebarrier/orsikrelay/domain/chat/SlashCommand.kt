package io.github.excludebarrier.orsikrelay.domain.chat

/** Closed REST equivalents, not the Hermes TUI command catalog. */
enum class SlashCommand(val word: String, val endpoint: String, val method: String, val path: String) {
    NEW("new", "session_create", "POST", "/api/sessions"),
    TITLE("title", "session_update", "PATCH", "/api/sessions/{session_id}"),
    STOP("stop", "run_stop", "POST", "/v1/runs/{run_id}/stop"),
    STEER("steer", "run_steer", "POST", "/v1/runs/{run_id}/steer"),
    BRANCH("branch", "session_fork", "POST", "/api/sessions/{session_id}/fork"),
    MODEL("model", "session_model_lock", "POST", "/api/sessions/{session_id}/model");

    val insertion: String get() = "/$word "
    val needsRun: Boolean get() = this == STOP || this == STEER
}

class SlashInput private constructor(val name: String, val argument: String) {
    val command: SlashCommand? get() = SlashCommand.entries.firstOrNull { it.word == name }
    // Command arguments may be private; do not include them in diagnostics.
    override fun toString() = "SlashInput([REDACTED])"

    companion object {
        /** `null` for ordinary text, including a path such as `/var/log/syslog` or `/notes.txt`. */
        fun parse(text: String): SlashInput? {
            if (!text.startsWith('/')) return null
            val boundary = text.indexOfFirst { it.isWhitespace() }.let { if (it < 0) text.length else it }
            val name = text.substring(1, boundary)
            if ('/' in name || '.' in name) return null
            return SlashInput(name, text.substring(boundary).trim())
        }
    }
}

fun slashSuggestions(text: String, available: Set<SlashCommand>): List<SlashCommand> {
    val input = SlashInput.parse(text) ?: return emptyList()
    if (text.any { it.isWhitespace() }) return emptyList()
    return SlashCommand.entries.filter { it in available && it.word.startsWith(input.name) }
}

fun interface SlashCommandSource {
    /** Authenticated capability read; failures return an empty set. Runs on IO. */
    fun available(profileId: String): Set<SlashCommand>
}
