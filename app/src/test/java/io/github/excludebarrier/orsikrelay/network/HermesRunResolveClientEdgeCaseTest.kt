package io.github.excludebarrier.orsikrelay.network

import io.github.excludebarrier.orsikrelay.domain.profile.SecretValue
import io.github.excludebarrier.orsikrelay.domain.run.LostRun
import io.github.excludebarrier.orsikrelay.domain.run.RunResolveError
import io.github.excludebarrier.orsikrelay.domain.run.RunResolveResult
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Adversarial inputs for `POST /v1/runs/{run_id}/resolve-unknown`: a closed
 * Stale result may only come from the documented answer, never from an
 * ambiguous body, a redirect or a malformed success.
 */
class HermesRunResolveClientEdgeCaseTest {

    private lateinit var server: MockWebServer
    private lateinit var client: HermesRunResolveClient
    private val key = SecretValue("fixture_key_123")
    private val lost = LostRun("adm_1", 4)
    private val failed = RunResolveResult.Failure(RunResolveError.Failed)

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
        client = HermesRunResolveClient(
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

    private fun resolve() = client.resolve(base, key, "run_1", lost)

    @Test
    fun aServerFailureCarryingStaleGenerationIsNotStale() {
        server.enqueue(
            json(
                """{"error":{"message":"boom","type":"server_error","code":"stale_generation"}}""",
                code = 500
            )
        )

        // SUSPECTED BUG: the body code alone decides Stale, without checking
        // the HTTP status, so a 500/401/404 carrying that code is reported as
        // the core's definitive stale_generation answer.
        assertEquals(failed, resolve())
    }

    @Test
    fun wrongTypedErrorEnvelopesAreNotStale() {
        listOf(
            """{"error":"stale_generation"}""",
            """{"error":{"code":123}}""",
            """{"error":[{"code":"stale_generation"}]}""",
            """{"code":"stale_generation"}""",
            """{"error":{"message":"stale_generation"}}"""
        ).forEach { body ->
            server.enqueue(json(body, code = 409))
            assertEquals("body $body", failed, resolve())
        }
    }

    @Test
    fun duplicateKeysAreRejectedNotGuessed() {
        server.enqueue(json("""{"run_id":"run_1","run_id":"run_1"}"""))
        assertEquals(failed, resolve())

        server.enqueue(json("""{"error":{"code":"stale_generation"},"error":{"code":"stale_generation"}}""", code = 409))
        assertEquals(failed, resolve())
    }

    @Test
    fun emptyAndOversizedBodiesFailClosed() {
        val oversized = """{"run_id":"run_1","padding":"${"x".repeat(70 * 1024)}"}"""

        server.enqueue(json(""))
        assertEquals(failed, resolve())

        server.enqueue(json(oversized))
        assertEquals(failed, resolve())

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setChunkedBody(oversized, 8192)
        )
        assertEquals(failed, resolve())
    }

    @Test
    fun aWrongOrMissingContentTypeIsRejected() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/html")
                .setBody("""{"run_id":"run_1"}""")
        )
        // SUSPECTED BUG: no requireJsonContentType call, so a non-JSON 200 is
        // accepted as Resolved; the hardened run clients reject it.
        assertEquals(failed, resolve())

        server.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"run_id":"run_1"}""")
        )
        assertEquals(failed, resolve())

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json; charset=utf-8")
                .setBody("""{"run_id":"run_1"}""")
        )
        assertEquals(RunResolveResult.Resolved, resolve())
    }

    @Test
    fun unexpectedStatusesFailAndRedirectsAreNotFollowed() {
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(MockResponse().setResponseCode(301).setHeader("Location", server.url("/moved")))
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://127.0.0.1:1/plain"))
        server.enqueue(
            MockResponse().setResponseCode(500)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"run_id":"run_1"}""")
        )

        repeat(4) { assertEquals(failed, resolve()) }
        assertEquals(4, server.requestCount)
    }

    @Test
    fun unicodeAdmissionIdIsSentExactlyOnceInTheBody() {
        server.enqueue(json("""{"run_id":"run_1"}"""))
        val admission = "adm-ü/1"

        assertEquals(RunResolveResult.Resolved, client.resolve(base, key, "run_1", LostRun(admission, 2)))

        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals(setOf("admission_id", "execution_generation"), body.keys().asSequence().toSet())
        assertEquals(admission, body.getString("admission_id"))
        assertEquals(2, body.getInt("execution_generation"))
    }

    @Test
    fun theLargestIntGenerationIsSentAsANumber() {
        server.enqueue(json("""{"run_id":"run_1"}"""))

        assertEquals(
            RunResolveResult.Resolved,
            client.resolve(base, key, "run_1", LostRun("adm_1", Int.MAX_VALUE))
        )

        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals(Int.MAX_VALUE, body.getInt("execution_generation"))
    }
}
