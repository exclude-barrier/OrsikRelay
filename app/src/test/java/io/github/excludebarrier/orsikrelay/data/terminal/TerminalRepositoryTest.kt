package io.github.excludebarrier.orsikrelay.data.terminal

import io.github.excludebarrier.orsikrelay.data.profile.ServerProfileMetadataStore
import io.github.excludebarrier.orsikrelay.data.profile.ServerProfileRepository
import io.github.excludebarrier.orsikrelay.domain.profile.SecretValue
import io.github.excludebarrier.orsikrelay.domain.profile.ServerProfile
import io.github.excludebarrier.orsikrelay.domain.terminal.TerminalAvailability
import io.github.excludebarrier.orsikrelay.domain.terminal.TerminalConnection
import io.github.excludebarrier.orsikrelay.domain.terminal.TerminalEvent
import io.github.excludebarrier.orsikrelay.domain.terminal.TerminalTransport
import io.github.excludebarrier.orsikrelay.security.credential.CredentialStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Fail-closed resolution for the production [TerminalRepository]: no profile,
 * no credential, or a broken store must never reach the transport.
 */
class TerminalRepositoryTest {

    private val profile = ServerProfile.create("p", "P", "https://example.invalid")

    private class FakeConnection : TerminalConnection {
        override fun send(bytes: ByteArray): Boolean = true
        override fun resize(columns: Int, rows: Int): Boolean = true
        override fun close() = Unit
    }

    private class Transport(
        var supported: Boolean = true,
        var supportsError: Exception? = null,
        var openError: Exception? = null,
        var openResult: TerminalConnection? = FakeConnection()
    ) : TerminalTransport {
        var supportCalls = 0
        var openCalls = 0
        val baseUrls = mutableListOf<String>()
        val keys = mutableListOf<SecretValue>()
        val callbacks = mutableListOf<(TerminalEvent) -> Unit>()

        override fun supports(baseUrl: String, apiKey: SecretValue): Boolean {
            supportCalls++
            baseUrls += baseUrl
            keys += apiKey
            supportsError?.let { throw it }
            return supported
        }

        override fun open(
            baseUrl: String,
            apiKey: SecretValue,
            onEvent: (TerminalEvent) -> Unit
        ): TerminalConnection? {
            openCalls++
            baseUrls += baseUrl
            keys += apiKey
            callbacks += onEvent
            openError?.let { throw it }
            return openResult
        }

        fun calls() = supportCalls + openCalls
    }

    private class Metadata(
        val profiles: List<ServerProfile>,
        var getError: Exception? = null
    ) : ServerProfileMetadataStore {
        override fun save(profile: ServerProfile) = Unit
        override fun get(profileId: String): ServerProfile? {
            getError?.let { throw it }
            return profiles.firstOrNull { it.id == profileId }
        }
        override fun list() = profiles
        override fun delete(profileId: String) = Unit
    }

    private class Credentials(
        val values: Map<String, SecretValue>,
        var getError: Exception? = null
    ) : CredentialStore {
        override fun put(profileId: String, secret: SecretValue) = Unit
        override fun get(profileId: String): SecretValue? {
            getError?.let { throw it }
            return values[profileId]
        }
        override fun delete(profileId: String) = Unit
    }

    private fun repository(
        transport: Transport = Transport(),
        metadata: Metadata = Metadata(listOf(profile)),
        credentials: Credentials = Credentials(mapOf("p" to SecretValue("k")))
    ): TerminalRepository = TerminalRepository(
        ServerProfileRepository(metadata, credentials, idFactory = { "x" }),
        credentials,
        transport
    )

    @Test
    fun availableAndOpenResolveTheProfilesUrlAndCredential() {
        val transport = Transport()
        val repository = repository(transport)

        assertEquals(TerminalAvailability.Available, repository.availability("p"))
        assertEquals(1, transport.supportCalls)
        assertEquals("https://example.invalid", transport.baseUrls.single())
        assertEquals("k", transport.keys.single().useRaw { it })

        val connection = repository.open("p") {}
        assertSame(transport.openResult, connection)
        assertEquals(1, transport.openCalls)
        assertEquals("k", transport.keys.last().useRaw { it })
    }

    @Test
    fun aSupportedNoIsUnsupportedNotFailed() {
        val transport = Transport(supported = false)

        assertEquals(TerminalAvailability.Unsupported, repository(transport).availability("p"))
    }

    @Test
    fun aMissingOrBlankProfileFailsClosedWithoutTheTransport() {
        val transport = Transport()
        val repository = repository(transport)

        assertEquals(TerminalAvailability.Failed, repository.availability("other"))
        assertNull(repository.open("other") {})
        assertEquals(TerminalAvailability.Failed, repository.availability("   "))
        assertNull(repository.open("   ") {})

        assertEquals(0, transport.calls())
    }

    @Test
    fun aMissingOrEmptyCredentialFailsClosedWithoutTheTransport() {
        val transport = Transport()

        assertEquals(
            TerminalAvailability.Failed,
            repository(transport, credentials = Credentials(emptyMap())).availability("p")
        )
        assertNull(repository(transport, credentials = Credentials(emptyMap())).open("p") {})

        assertEquals(
            TerminalAvailability.Failed,
            repository(transport, credentials = Credentials(mapOf("p" to SecretValue("")))).availability("p")
        )
        assertNull(
            repository(transport, credentials = Credentials(mapOf("p" to SecretValue("")))).open("p") {}
        )

        assertEquals(0, transport.calls())
    }

    @Test
    fun aThrowingStoreOrTransportIsCaught() {
        val transport = Transport()
        assertEquals(
            TerminalAvailability.Failed,
            repository(transport, metadata = Metadata(listOf(profile), getError = IllegalStateException())).availability("p")
        )
        assertNull(
            repository(transport, metadata = Metadata(listOf(profile), getError = IllegalStateException())).open("p") {}
        )
        assertEquals(
            TerminalAvailability.Failed,
            repository(transport, credentials = Credentials(emptyMap(), getError = IllegalStateException())).availability("p")
        )
        assertNull(
            repository(transport, credentials = Credentials(emptyMap(), getError = IllegalStateException())).open("p") {}
        )
        assertEquals(0, transport.calls())

        assertEquals(
            TerminalAvailability.Failed,
            repository(Transport(supportsError = IllegalStateException())).availability("p")
        )
        assertNull(repository(Transport(openError = IllegalStateException())).open("p") {})
    }

    @Test
    fun aNullTransportConnectionIsPassedThrough() {
        val transport = Transport(openResult = null)

        assertNull(repository(transport).open("p") {})
        assertEquals(1, transport.openCalls)
    }
}
