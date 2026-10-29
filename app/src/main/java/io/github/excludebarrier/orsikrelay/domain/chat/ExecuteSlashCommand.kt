package io.github.excludebarrier.orsikrelay.domain.chat

sealed interface SlashCommandResult {
    data class OpenSession(val sessionId: String) : SlashCommandResult
    data object Done : SlashCommandResult
    /** Includes ambiguous transport outcomes; never means success. */
    data object Failed : SlashCommandResult
}

fun interface SlashCommandExecutor {
    fun execute(profileId: String, sessionId: String, command: SlashCommand, argument: String): SlashCommandResult
}

/** Explicit user-submitted REST operation; no queue, retry or lifecycle trigger. */
class ExecuteSlashCommand(private val executor: SlashCommandExecutor) {
    operator fun invoke(profileId: String, sessionId: String, input: SlashInput): SlashCommandResult {
        val command = input.command ?: return SlashCommandResult.Failed
        if (!isValid(input)) return SlashCommandResult.Failed
        return executor.execute(profileId, sessionId, command, input.argument)
    }

    companion object {
        fun isValid(input: SlashInput): Boolean = when (input.command) {
            SlashCommand.NEW, SlashCommand.STOP, SlashCommand.BRANCH -> input.argument.isEmpty()
            SlashCommand.TITLE -> input.argument.isNotBlank() && input.argument.length <= 512 &&
                input.argument.none { it.isISOControl() }
            SlashCommand.STEER -> ChatMessageValidator.validate(input.argument).isSuccess
            SlashCommand.MODEL -> input.argument.split("::", limit = 2).let {
                it.size == 2 && SessionModelOption(it[0], it[1]).isValid()
            }
            else -> false
        }
    }
}
