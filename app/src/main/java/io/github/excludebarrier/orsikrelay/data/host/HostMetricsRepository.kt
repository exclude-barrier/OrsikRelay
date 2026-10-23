package io.github.excludebarrier.orsikrelay.data.host

import io.github.excludebarrier.orsikrelay.data.profile.ServerProfileRepository
import io.github.excludebarrier.orsikrelay.domain.host.HostMetricsResult
import io.github.excludebarrier.orsikrelay.domain.host.HostMetricsSource
import io.github.excludebarrier.orsikrelay.domain.host.HostMetricsTransport
import io.github.excludebarrier.orsikrelay.network.HermesHostMetricsClient
import io.github.excludebarrier.orsikrelay.security.credential.CredentialStore
import java.util.concurrent.ConcurrentHashMap

/**
 * Production [HostMetricsSource]. Once the server advertised `host_metrics`
 * that is kept for the process, so a poll is a single GET; a "no" is asked
 * again on the next poll, so a bridge update shows up without a restart. A
 * missing profile or credential fails closed.
 */
class HostMetricsRepository(
    private val profileRepository: ServerProfileRepository,
    private val credentialStore: CredentialStore,
    private val transport: HostMetricsTransport = HermesHostMetricsClient()
) : HostMetricsSource {

    private val supported = ConcurrentHashMap<String, Boolean>()

    override fun read(profileId: String): HostMetricsResult {
        if (profileId.isBlank()) return HostMetricsResult.Failed
        val profile = try {
            profileRepository.get(profileId)
        } catch (_: Exception) {
            null
        } ?: return HostMetricsResult.Failed
        val credential = try {
            credentialStore.get(profileId)
        } catch (_: Exception) {
            null
        }
        if (credential == null || credential.isEmpty()) return HostMetricsResult.Failed
        return try {
            val advertised = supported[profileId] == true ||
                transport.supports(profile.baseUrl, credential).also { if (it) supported[profileId] = true }
            if (advertised) transport.read(profile.baseUrl, credential) else HostMetricsResult.Unsupported
        } catch (_: Exception) {
            HostMetricsResult.Failed
        }
    }
}
