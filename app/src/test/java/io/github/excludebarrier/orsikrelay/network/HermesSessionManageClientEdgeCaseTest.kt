package io.github.excludebarrier.orsikrelay.network

import io.github.excludebarrier.orsikrelay.domain.profile.SecretValue
import io.github.excludebarrier.orsikrelay.domain.session.SessionManageError
import io.github.excludebarrier.orsikrelay.domain.session.SessionManageResult
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

/**
 * Adversarial inputs for the management writes and the capability read:
 * malformed capabilities, ambiguous bodies, wrong media types, redirects and
 * ids that must never reach a request.
 */
class HermesSessionManageClientEdgeCaseTest {

    private lateinit var server: MockWebServer
    private lateinit var clientCertificates: HandshakeCertificates
    private lateinit var client: HermesSessionManageClient
    private val key = SecretValue("fixture_key_123")

    @Before
    fun setUp() {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        clientCertificates = HandshakeCertificates.Builder()
            .addTrustedCertificate(certificate.certificate)
            .build()
        server = MockWebServer().apply {
            useHttps(serverCertificates.sslSocketFactory(), false)
            start()
        }
        client = HermesSessionManageClient(
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

    private fun json(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun capabilities(archive: String = "true", delete: String = "true") =
        """{"features":{"session_archive":$archive,"session_delete":$delete},
            "endpoints":{
              "session_update":{"method":"PATCH","path":"/api/sessions/{session_id}"},
              "session_delete":{"method":"DELETE","path":"/api/sessions/{session_id}"}}}""".trimIndent()

    private val deleted get() = """{"object":"hermes.session.deleted","deleted":true}"""
    private val failed = SessionManageResult.Failure(SessionManageError.Failed)

    private fun delete() = client.delete(base, key, "s1")

    @Test
    fun capabilitiesWithWrongTypesNeverEnableManagement() {
        listOf(
            """{"features":[],"endpoints":{}}""",
            """{"features":"all","endpoints":{}}""",
            """{"features":{"session_archive":1,"session_delete":true},"endpoints":{}}""",
            """{"features":{"session_archive":true,"session_delete":"true"},"endpoints":{}}""",
            """{"features":{"session_archive":true,"session_delete":true},"endpoints":{"session_update":"PATCH","session_delete":{"method":"DELETE","path":"/api/sessions/{session_id}"}}}""",
            """{"features":{"session_archive":true,"session_delete":true},"endpoints":{"session_update":{"method":["PATCH"],"path":"/api/sessions/{session_id}"},"session_delete":{"method":"DELETE","path":"/api/sessions/{session_id}"}}}""",
            """{"features":{"session_archive":true,"session_delete":true},"endpoints":{"session_update":{"method":"PATCH","path":"/api/sessions/{id}"},"session_delete":{"method":"DELETE","path":"/api/sessions/{session_id}"}}}""",
            """{"features":{"session_archive":true,"session_delete":true},"endpoints":{"session_update":{"method":"PATCH","path":"/api/sessions/{session_id}"},"session_delete":{"method":"POST","path":"/api/sessions/{session_id}"}}}"""
        ).forEach { body ->
            server.enqueue(json(body))
            assertFalse("body $body", client.supportsManagement(base, key))
        }
        assertEquals(8, server.requestCount)
    }

    @Test
    fun duplicateKeysAreRejectedNotGuessed() {
        server.enqueue(json("""
            {"features":{"session_archive":true},"features":{"session_delete":true},"endpoints":{}}
        """.trimIndent()))
        assertFalse(client.supportsManagement(base, key))

        server.enqueue(json("""{"object":"hermes.session.deleted","object":"hermes.session.deleted","deleted":true}"""))
        assertEquals(failed, delete())
    }

    @Test
    fun capabilitiesOverTheBoundAreRejected() {
        val oversized = """{"features":{},"endpoints":{},"padding":"${"x".repeat(300 * 1024)}"}"""

        server.enqueue(json(oversized))
        assertFalse(client.supportsManagement(base, key))

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setChunkedBody(oversized, 8192)
        )
        assertFalse(client.supportsManagement(base, key))

        assertEquals(2, server.requestCount)
    }

    @Test
    fun emptyOrOversizedWriteBodiesFailClosed() {
        val oversized = """{"object":"hermes.session.deleted","deleted":true,"padding":"${"x".repeat(600 * 1024)}"}"""

        server.enqueue(json(""))
        server.enqueue(json(oversized))
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setChunkedBody(oversized, 8192)
        )
        server.enqueue(json("not json"))
        repeat(4) { assertEquals(failed, delete()) }

        server.enqueue(json(""))
        assertEquals(failed, client.setArchived(base, key, "s1", archived = true))
    }

    @Test
    fun unexpectedStatusesFailAndRedirectsAreNotFollowed() {
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(MockResponse().setResponseCode(301).setHeader("Location", server.url("/elsewhere")))
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://127.0.0.1:1/plain"))
        server.enqueue(
            MockResponse().setResponseCode(500)
                .setHeader("Content-Type", "application/json")
                .setBody(deleted)
        )

        repeat(4) { assertEquals(failed, delete()) }
        assertEquals(4, server.requestCount)
    }

    @Test
    fun aWrongOrMissingContentTypeIsRejected() {
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("Content-Type", "text/html").setBody(deleted)
        )
        // SUSPECTED BUG: the write path never calls requireJsonContentType, so
        // a 200 with any media type is treated as the success contract.
        assertEquals(failed, delete())

        server.enqueue(MockResponse().setResponseCode(200).setBody(deleted))
        assertEquals(failed, delete())

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/html")
                .setBody(capabilities())
        )
        // SUSPECTED BUG: the capability read is tolerant of the same wrong
        // media type, unlike the other hardened one-shot clients.
        assertFalse(client.supportsManagement(base, key))

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json; charset=utf-8")
                .setBody(deleted)
        )
        assertEquals(SessionManageResult.Success, delete())
    }

    @Test
    fun aWrongTypedDeletedFlagIsNotProofOfANotFound() {
        server.enqueue(json("""{"object":"hermes.session.deleted","deleted":"true"}"""))
        // SUSPECTED BUG: a malformed success body is classified NotFound (as if
        // the server proved the row is gone) instead of Failed.
        assertEquals(failed, delete())

        server.enqueue(json("""{"object":"hermes.session.deleted","deleted":1}"""))
        assertEquals(failed, delete())
    }

    @Test
    fun sessionIdsAreBoundedAndEncodedAsOneSegment() {
        server.enqueue(json(deleted))
        server.enqueue(json(deleted))
        server.enqueue(json(deleted))

        assertEquals(SessionManageResult.Success, client.delete(base, key, "sitzung-ü"))
        assertEquals(SessionManageResult.Success, client.delete(base, key, "a/b c"))
        assertEquals(SessionManageResult.Success, client.delete(base, key, "x".repeat(256)))

        val first = server.takeRequest()
        assertEquals("/p/api/sessions/sitzung-%C3%BC", first.requestUrl!!.encodedPath)
        val second = server.takeRequest()
        assertEquals("/p/api/sessions/a%2Fb%20c", second.requestUrl!!.encodedPath)

        assertEquals(3, server.requestCount)
        assertEquals(failed, client.delete(base, key, "x".repeat(257)))
        assertEquals(failed, client.delete(base, key, "a\u0001b"))
        assertEquals(failed, client.delete(base, key, "   "))
        assertEquals(3, server.requestCount)
    }

    @Test
    fun aSlowWriteResponseFailsClosedInsteadOfHanging() {
        val slowClient = HermesSessionManageClient(
            OkHttpClient.Builder()
                .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
                .readTimeout(300, TimeUnit.MILLISECONDS)
                .callTimeout(500, TimeUnit.MILLISECONDS)
                .build()
        )
        val slowBody = """{"object":"hermes.session.deleted","deleted":true,"padding":"${"x".repeat(8 * 1024)}"}"""
        server.enqueue(json(slowBody).throttleBody(32, 100, TimeUnit.MILLISECONDS))

        assertEquals(failed, slowClient.delete(base, key, "s1"))
    }
}
