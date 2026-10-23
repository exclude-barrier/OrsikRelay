package io.github.excludebarrier.orsikrelay.data.terminal

import io.github.excludebarrier.orsikrelay.data.profile.ServerProfileRepository
import io.github.excludebarrier.orsikrelay.domain.profile.SecretValue
import io.github.excludebarrier.orsikrelay.domain.terminal.TerminalAvailability
import io.github.excludebarrier.orsikrelay.domain.terminal.TerminalConnection
import io.github.excludebarrier.orsikrelay.domain.terminal.TerminalEvent
import io.github.excludebarrier.orsikrelay.domain.terminal.TerminalSource
import io.github.excludebarrier.orsikrelay.domain.terminal.TerminalTransport
import io.github.excludebarrier.orsikrelay.network.HermesTerminalClient
import io.github.excludebarrier.orsikrelay.security.credential.CredentialStore

/**
 * Production [TerminalSource]: resolves the profile's server and device
 * credential; a missing one fails closed. Availability is asked every time
 * the screen opens (the operator may switch the terminal off at any time).
 */
class TerminalRepository(
    private val profileRepository: ServerProfileRepository,
    private val credentialStore: CredentialStore,
    private val transport: TerminalTransport = HermesTerminalClient()
) : TerminalSource {

    override fun availability(profileId: String): TerminalAvailability {
        val (baseUrl, credential) = resolve(profileId) ?: return TerminalAvailability.Failed
        return try {
            if (transport.supports(baseUrl, credential)) TerminalAvailability.Available else TerminalAvailability.Unsupported
        } catch (_: Exception) {
            TerminalAvailability.Failed
        }
    }

    override fun open(profileId: String, onEvent: (TerminalEvent) -> Unit): TerminalConnection? {
        val (baseUrl, credential) = resolve(profileId) ?: return null
        return try {
            transport.open(baseUrl, credential, onEvent)
        } catch (_: Exception) {
            null
        }
    }

    private fun resolve(profileId: String): Pair<String, SecretValue>? {
        if (profileId.isBlank()) return null
        val profile = try {
            profileRepository.get(profileId)
        } catch (_: Exception) {
            null
        } ?: return null
        val credential = try {
            credentialStore.get(profileId)
        } catch (_: Exception) {
            null
        }
        if (credential == null || credential.isEmpty()) return null
        return profile.baseUrl to credential
    }
}
