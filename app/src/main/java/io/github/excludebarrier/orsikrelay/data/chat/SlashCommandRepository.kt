package io.github.excludebarrier.orsikrelay.data.chat

import io.github.excludebarrier.orsikrelay.data.profile.ServerProfileRepository
import io.github.excludebarrier.orsikrelay.domain.chat.SlashCommand
import io.github.excludebarrier.orsikrelay.domain.chat.SlashCommandSource
import io.github.excludebarrier.orsikrelay.network.HermesSlashClient
import io.github.excludebarrier.orsikrelay.security.credential.CredentialStore
import io.github.excludebarrier.orsikrelay.domain.chat.SlashCommandExecutor
import io.github.excludebarrier.orsikrelay.domain.chat.SlashCommandResult
import io.github.excludebarrier.orsikrelay.domain.session.SessionCreator
import io.github.excludebarrier.orsikrelay.domain.session.SessionCreateResult
import io.github.excludebarrier.orsikrelay.data.session.SessionCreateRepository
import io.github.excludebarrier.orsikrelay.domain.chat.ModelOptionsSource
import io.github.excludebarrier.orsikrelay.domain.chat.ModelOptionsResult
import io.github.excludebarrier.orsikrelay.domain.chat.SessionModelOption
import io.github.excludebarrier.orsikrelay.domain.session.SessionManageResult
import io.github.excludebarrier.orsikrelay.network.HermesSessionManageClient

class SlashCommandRepository(
    private val profiles: ServerProfileRepository,
    private val credentials: CredentialStore,
    private val client: HermesSlashClient = HermesSlashClient(),
    private val creator: SessionCreator = SessionCreateRepository(profiles, credentials),
    private val titleClient: HermesSessionManageClient =
        HermesSessionManageClient()
) : SlashCommandSource, SlashCommandExecutor, ModelOptionsSource {
    override fun loadModels(profileId: String): ModelOptionsResult {
        return try {
            val profile = profiles.get(profileId) ?: return ModelOptionsResult.Failed
            val key = credentials.get(profileId) ?: return ModelOptionsResult.Failed
            client.models(profile.baseUrl, key)
        } catch (_: Exception) { ModelOptionsResult.Failed }
    }

    override fun execute(profileId: String, sessionId: String, command: SlashCommand, argument: String): SlashCommandResult {
        return try {
            when (command) {
                SlashCommand.NEW -> when (val result = creator.createSession(profileId, null)) {
                    is SessionCreateResult.Success -> SlashCommandResult.OpenSession(result.sessionId)
                    is SessionCreateResult.Failure -> SlashCommandResult.Failed
                }
                SlashCommand.TITLE -> {
                    val profile = profiles.get(profileId) ?: return SlashCommandResult.Failed
                    val key = credentials.get(profileId) ?: return SlashCommandResult.Failed
                    if (titleClient.rename(profile.baseUrl, key, sessionId, argument) ==
                        SessionManageResult.Success)
                        SlashCommandResult.Done else SlashCommandResult.Failed
                }
                SlashCommand.BRANCH -> {
                    val profile = profiles.get(profileId) ?: return SlashCommandResult.Failed
                    val key = credentials.get(profileId) ?: return SlashCommandResult.Failed
                    client.fork(profile.baseUrl, key, sessionId)
                }
                SlashCommand.MODEL -> {
                    val parts = argument.split("::", limit = 2)
                    if (parts.size != 2) return SlashCommandResult.Failed
                    val profile = profiles.get(profileId) ?: return SlashCommandResult.Failed
                    val key = credentials.get(profileId) ?: return SlashCommandResult.Failed
                    client.lockModel(profile.baseUrl, key, sessionId, SessionModelOption(parts[0], parts[1]))
                }
                else -> SlashCommandResult.Failed
            }
        } catch (_: Exception) {
            SlashCommandResult.Failed
        }
    }

    override fun available(profileId: String): Set<SlashCommand> {
        if (profileId.isBlank()) return emptySet()
        return try {
            val profile = profiles.get(profileId) ?: return emptySet()
            val key = credentials.get(profileId) ?: return emptySet()
            client.available(profile.baseUrl, key)
        } catch (_: Exception) {
            emptySet()
        }
    }
}
