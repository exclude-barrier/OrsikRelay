package io.github.excludebarrier.orsikrelay.network

import io.github.excludebarrier.orsikrelay.domain.profile.SecretValue
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Adversarial capabilities and open payloads: the client advertises the
 * terminal only for the exact feature+endpoint contract, and it never sends
 * a request for a key or URL it cannot trust.
 */
class HermesTerminalClientEdgeCaseTest {

    private lateinit var server: MockWebServer
    private lateinit var client: HermesTerminalClient
    private val key = SecretValue("fixture_key_123")

    @Before
    fun setUp() {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientCertificates = HandshakeCertificates.Builder()
            .addTrustedCertificate(certificate.certificate)
            .build()
        server = MockWebServer().apply {
            useHttps(serverCertificates.sslSocketFactory(), false)
            start()
        }
        client = HermesTerminalClient(
            OkHttpClient.Builder()
                .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
                .build()
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private val base get() = server.url("/p").toString().trimEnd('/')

    private fun json(body: String, code: Int = 200, contentType: String = "application/json") =
        MockResponse().setResponseCode(code).setHeader("Content-Type", contentType).setBody(body)

    private fun capabilities(
        feature: Boolean = true,
        method: String = "GET",
        path: String = "/v1/terminal"
    ) = """
        {"features":{"terminal":$feature},
         "endpoints":{"terminal":{"method":"$method","path":"$path"}}}
    """.trimIndent()

    @Test
    fun supportsRejectsAnyDeviationFromTheExactFeatureAndEndpoint() {
        val bodies = listOf(
            capabilities(feature = false),
            """{"features":{},"endpoints":{"terminal":{"method":"GET","path":"/v1/terminal"}}}""",
            """{"endpoints":{"terminal":{"method":"GET","path":"/v1/terminal"}}}""",
            capabilities(method = "POST"),
            capabilities(path = "/v1/terminal/"),
            capabilities(path = "/v1/term"),
            capabilities(path = "/v1/terminal?x=1"),
            """{"features":{"terminal":true},"endpoints":{}}""",
            """{"features":{"terminal":true}}""",
            """{"features":{"terminal":"true"},"endpoints":{"terminal":{"method":"GET","path":"/v1/terminal"}}}"""
        )
        bodies.forEach { server.enqueue(json(it)) }

        bodies.indices.forEach { index ->
            assertFalse("body $index: ${bodies[index]}", client.supports(base, key))
        }
    }

    @Test
    fun supportsRejectsNon200NonJsonAndInvalidJson() {
        server.enqueue(json(capabilities(), code = 403))
        server.enqueue(json(capabilities(), code = 500))
        server.enqueue(json(capabilities(), contentType = "text/plain"))
        server.enqueue(json(capabilities(), contentType = "application/octet-stream"))
        server.enqueue(json("{not json"))

        repeat(5) { assertFalse("response $it", client.supports(base, key)) }
    }

    @Test
    fun supportsRejectsAnOversizedCapabilitiesBody() {
        server.enqueue(json("{" + " ".repeat(HermesTerminalClient.MAX_CAPABILITIES_BYTES) + "}"))

        assertFalse(client.supports(base, key))
    }

    @Test
    fun anUnsafeOrEmptyKeyNeverReachesTheNetwork() {
        val unsafe = listOf(
            SecretValue(""),
            SecretValue("key with space"),
            SecretValue("key\nwith newline"),
            SecretValue("key\twith tab"),
            SecretValue("key\u0000with nul"),
            SecretValue("k".repeat(4097))
        )

        unsafe.forEach { secret ->
            assertFalse("key must be rejected", client.supports(base, secret))
            assertNull("open must fail closed", client.open(base, secret) {})
        }
        // Base checks run before any request; nothing was sent.
        assertEquals("no request may be made", 0, server.requestCount)
    }

    @Test
    fun aPlainHttpOrUnparseableBaseUrlNeverReachesTheNetwork() {
        val rejected = listOf(
            base.replace("https://", "http://"),
            base.replace("https://", "ftp://"),
            "",
            "not a url",
            "https://",
            "https://user:pass@localhost/p"
        )

        rejected.forEach { url ->
            assertFalse("supports($url)", client.supports(url, key))
            assertNull("open($url)", client.open(url, key) {})
        }
        assertEquals("no request may be made", 0, server.requestCount)
    }
}
