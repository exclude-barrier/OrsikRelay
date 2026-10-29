package io.github.excludebarrier.orsikrelay.data.chat

import io.github.excludebarrier.orsikrelay.data.profile.ServerProfileMetadataStore
import io.github.excludebarrier.orsikrelay.data.profile.ServerProfileRepository
import io.github.excludebarrier.orsikrelay.data.session.SessionCreateRepository
import io.github.excludebarrier.orsikrelay.domain.chat.*
import io.github.excludebarrier.orsikrelay.domain.profile.SecretValue
import io.github.excludebarrier.orsikrelay.domain.profile.ServerProfile
import io.github.excludebarrier.orsikrelay.network.HermesSessionCreateClient
import io.github.excludebarrier.orsikrelay.network.HermesSessionManageClient
import io.github.excludebarrier.orsikrelay.network.HermesSlashClient
import io.github.excludebarrier.orsikrelay.security.credential.CredentialStore
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SlashCommandRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: SlashCommandRepository
    private var profile: ServerProfile? = null
    private var credential: SecretValue? = SecretValue("synthetic-test-credential")
    private val credentialReads = mutableListOf<String>()

    @Before fun setUp() {
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val tls = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val trusted = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        server = MockWebServer().apply { useHttps(tls.sslSocketFactory(), false); start() }
        val http = OkHttpClient.Builder().sslSocketFactory(trusted.sslSocketFactory(), trusted.trustManager).build()
        profile = ServerProfile.create("p", "Test server", server.url("/prefix").toString())
        val credentials = object : CredentialStore {
            override fun get(profileId: String): SecretValue? { credentialReads += profileId; return credential }
            override fun put(profileId: String, secret: SecretValue) { credential = secret }
            override fun delete(profileId: String) { credential = null }
        }
        val profiles = ServerProfileRepository(object : ServerProfileMetadataStore {
            override fun get(profileId: String) = profile?.takeIf { it.id == profileId }
            override fun save(profile: ServerProfile) { this@SlashCommandRepositoryTest.profile = profile }
            override fun list() = listOfNotNull(profile)
            override fun delete(profileId: String) { profile = null }
        }, credentials)
        repository = SlashCommandRepository(profiles, credentials, HermesSlashClient(http),
            SessionCreateRepository(profiles, credentials, HermesSessionCreateClient(http)), HermesSessionManageClient(http))
    }

    @After fun tearDown() { server.shutdown() }

    private fun reply(status: Int, body: String) {
        server.enqueue(MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body))
    }

    @Test fun explicitUseCaseTraversesRepositoryAndRealClientsForEachSessionOperation() {
        val execute = ExecuteSlashCommand(repository)
        reply(201, """{"object":"hermes.session","session":{"id":"new"}}""")
        assertEquals(SlashCommandResult.OpenSession("new"), execute("p", "s1", SlashInput.parse("/new")!!))
        reply(200, """{"object":"hermes.session","session":{"id":"s1","title":"New title"}}""")
        assertEquals(SlashCommandResult.Done, execute("p", "s1", SlashInput.parse("/title New title")!!))
        reply(201, """{"object":"hermes.session","session":{"id":"child","parent_session_id":"s1"}}""")
        assertEquals(SlashCommandResult.OpenSession("child"), execute("p", "s1", SlashInput.parse("/branch")!!))
        reply(200, """{"object":"hermes.session.model_lock","session_id":"s1","runtime":{"model_lock":"accepted","requested":{"provider":"provider","model":"model"}}}""")
        assertEquals(SlashCommandResult.Done, execute("p", "s1", SlashInput.parse("/model provider::model")!!))
        assertEquals(listOf("POST", "PATCH", "POST", "POST"), List(4) { server.takeRequest().method })
        assertEquals(listOf("p", "p", "p", "p"), credentialReads)
    }

    @Test fun missingProfileOrCredentialFailsClosedWithoutTransportOrSecretDisclosure() {
        val execute = ExecuteSlashCommand(repository)
        assertEquals(SlashCommandResult.Failed, execute("missing", "s1", SlashInput.parse("/branch")!!))
        assertTrue(credentialReads.isEmpty())
        credential = null
        assertEquals(SlashCommandResult.Failed, execute("p", "s1", SlashInput.parse("/branch")!!))
        assertEquals(ModelOptionsResult.Failed, repository.loadModels("p"))
        assertTrue(repository.available("p").isEmpty())
        assertEquals(0, server.requestCount)
    }
}
