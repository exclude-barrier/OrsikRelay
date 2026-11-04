package io.github.excludebarrier.orsikrelay.network

import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A real TLS handshake against a server that demands a client certificate
 * from its own CA (like the public reverse proxy): the phone's identity is
 * presented to the bound host only.
 */
class ClientCertificateKeyManagerTest {

    private lateinit var server: MockWebServer
    private lateinit var keyManager: ClientCertificateKeyManager
    private lateinit var client: OkHttpClient

    private val clientCa = HeldCertificate.Builder().certificateAuthority(0).commonName("test client CA").build()
    private val phone = HeldCertificate.Builder().signedBy(clientCa).commonName("pixel").build()
    private val alias = "Orsik Relay"

    private val identities = object : ClientIdentitySource {
        override fun privateKey(alias: String): PrivateKey? =
            if (alias == this@ClientCertificateKeyManagerTest.alias) phone.keyPair.private else null

        override fun certificateChain(alias: String): Array<X509Certificate>? =
            if (alias == this@ClientCertificateKeyManagerTest.alias) arrayOf(phone.certificate, clientCa.certificate) else null
    }

    @Before
    fun setUp() {
        val serverCertificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverHandshake = HandshakeCertificates.Builder()
            .heldCertificate(serverCertificate)
            .addTrustedCertificate(clientCa.certificate)
            .build()
        server = MockWebServer().apply {
            useHttps(serverHandshake.sslSocketFactory(), false)
            requireClientAuth()
            start()
        }
        keyManager = ClientCertificateKeyManager { identities }
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(serverCertificate.certificate).build()
        val context = SSLContext.getInstance("TLS")
        context.init(arrayOf(keyManager), arrayOf(trust.trustManager), null)
        client = OkHttpClient.Builder()
            .sslSocketFactory(context.socketFactory, trust.trustManager)
            .retryOnConnectionFailure(false)
            .build()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun call(): Int = client.newCall(Request.Builder().url(server.url("/v1/server")).build())
        .execute().use { it.code }

    @Test
    fun theBoundHostGetsTheCertificateAndTheHandshakeSucceeds() {
        keyManager.bind(server.hostName, alias)
        server.enqueue(MockResponse().setResponseCode(200))

        assertEquals(200, call())
        val presented = server.takeRequest().handshake?.peerCertificates.orEmpty()
        assertEquals(phone.certificate, presented.firstOrNull())
    }

    @Test
    fun anUnboundHostGetsNoCertificateAndTheServerRefuses() {
        server.enqueue(MockResponse().setResponseCode(200))
        val refused = runCatching { call() }.exceptionOrNull()
        assertTrue("expected a TLS failure, got $refused", refused is SSLHandshakeException || refused is java.io.IOException)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun anUnbindingStopsPresentingTheCertificate() {
        keyManager.bind(server.hostName, alias)
        keyManager.bind(server.hostName, null)
        server.enqueue(MockResponse().setResponseCode(200))
        assertTrue(runCatching { call() }.isFailure)
        assertEquals(0, server.requestCount)
    }
}
