package io.github.excludebarrier.orsikrelay.data.host

import io.github.excludebarrier.orsikrelay.data.profile.ServerProfileMetadataStore
import io.github.excludebarrier.orsikrelay.data.profile.ServerProfileRepository
import io.github.excludebarrier.orsikrelay.domain.host.HostMetrics
import io.github.excludebarrier.orsikrelay.domain.host.HostMetricsResult
import io.github.excludebarrier.orsikrelay.domain.host.HostMetricsTransport
import io.github.excludebarrier.orsikrelay.domain.profile.SecretValue
import io.github.excludebarrier.orsikrelay.domain.profile.ServerProfile
import io.github.excludebarrier.orsikrelay.security.credential.CredentialStore
import org.junit.Assert.assertEquals
import org.junit.Test

class HostMetricsRepositoryTest {

    private class Transport(var supported: Boolean) : HostMetricsTransport {
        var supportCalls = 0
        var readCalls = 0
        var readResult: HostMetricsResult = HostMetricsResult.Ready(HostMetrics(1.0, 1, 1, 2, null, emptyList()))
        var failRead = false
        override fun supports(baseUrl: String, apiKey: SecretValue): Boolean {
            supportCalls++
            return supported
        }
        override fun read(baseUrl: String, apiKey: SecretValue): HostMetricsResult {
            readCalls++
            if (failRead) throw IllegalStateException()
            return readResult
        }
    }

    private class Metadata(val profiles: List<ServerProfile>) : ServerProfileMetadataStore {
        override fun save(profile: ServerProfile) = Unit
        override fun get(profileId: String) = profiles.firstOrNull { it.id == profileId }
        override fun list() = profiles
        override fun delete(profileId: String) = Unit
    }

    private class Credentials(val values: Map<String, SecretValue>) : CredentialStore {
        override fun put(profileId: String, secret: SecretValue) = Unit
        override fun get(profileId: String) = values[profileId]
        override fun delete(profileId: String) = Unit
    }

    private fun repository(transport: Transport, withKey: Boolean = true): HostMetricsRepository {
        val credentials = Credentials(if (withKey) mapOf("p" to SecretValue("k")) else emptyMap())
        return HostMetricsRepository(
            ServerProfileRepository(Metadata(listOf(ServerProfile.create("p", "P", "https://example.invalid"))), credentials, idFactory = { "x" }),
            credentials,
            transport
        )
    }

    @Test
    fun aYesIsKeptSoAPollIsOneGet() {
        val transport = Transport(supported = true)
        val repository = repository(transport)
        repeat(3) { assertEquals(HostMetricsResult.Ready(HostMetrics(1.0, 1, 1, 2, null, emptyList())), repository.read("p")) }
        assertEquals(1, transport.supportCalls)
        assertEquals(3, transport.readCalls)
    }

    @Test
    fun aNoIsAskedAgainSoABridgeUpdateShowsUp() {
        val transport = Transport(supported = false)
        val repository = repository(transport)
        assertEquals(HostMetricsResult.Unsupported, repository.read("p"))
        transport.supported = true
        assertEquals(HostMetricsResult.Ready(HostMetrics(1.0, 1, 1, 2, null, emptyList())), repository.read("p"))
        assertEquals(2, transport.supportCalls)
    }

    @Test
    fun aFailedReadRechecksCapabilitiesOnTheNextPoll() {
        val transport = Transport(supported = true)
        val repository = repository(transport)
        repository.read("p")
        transport.readResult = HostMetricsResult.Failed
        assertEquals(HostMetricsResult.Failed, repository.read("p"))
        transport.supported = false
        assertEquals(HostMetricsResult.Unsupported, repository.read("p"))
        assertEquals(2, transport.supportCalls)
        assertEquals(2, transport.readCalls)
    }

    @Test
    fun aThrownReadAlsoInvalidatesTheCapabilityCache() {
        val transport = Transport(supported = true)
        val repository = repository(transport)
        repository.read("p")
        transport.failRead = true
        assertEquals(HostMetricsResult.Failed, repository.read("p"))
        transport.supported = false
        transport.failRead = false
        assertEquals(HostMetricsResult.Unsupported, repository.read("p"))
        assertEquals(2, transport.supportCalls)
    }

    @Test
    fun noKeyOrNoProfileFailsWithoutARequest() {
        val transport = Transport(supported = true)
        assertEquals(HostMetricsResult.Failed, repository(transport, withKey = false).read("p"))
        assertEquals(HostMetricsResult.Failed, repository(transport).read("other"))
        assertEquals(0, transport.supportCalls + transport.readCalls)
    }
}
