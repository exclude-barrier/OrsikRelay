package io.github.excludebarrier.orsikrelay.network

import io.github.excludebarrier.orsikrelay.domain.connection.ConnectionFailureReason
import io.github.excludebarrier.orsikrelay.domain.connection.HermesConnectionResult
import io.github.excludebarrier.orsikrelay.domain.profile.ProfileDraft
import io.github.excludebarrier.orsikrelay.domain.profile.SecretValue
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class HermesConnectionClientTest {

    private lateinit var server: MockWebServer
    private lateinit var testCertificate: HeldCertificate
    private lateinit var trustedClient: OkHttpClient
    private lateinit var client: HermesConnectionClient

    @Before
    fun setUp() {
        testCertificate = HeldCertificate.Builder()
            .addSubjectAlternativeName("localhost")
            .build()
        val serverCertificates = HandshakeCertificates.Builder()
            .heldCertificate(testCertificate)
            .build()
        val clientCertificates = HandshakeCertificates.Builder()
            .addTrustedCertificate(testCertificate.certificate)
            .build()
        server = MockWebServer().apply {
            useHttps(serverCertificates.sslSocketFactory(), false)
            start()
        }
        trustedClient = OkHttpClient.Builder()
            .sslSocketFactory(
                clientCertificates.sslSocketFactory(),
                clientCertificates.trustManager
            )
            .build()
        client = HermesConnectionClient(trustedClient)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun successfulProbeValidatesHealthAndCapabilities() {
        enqueueValidResponses()

        val result = client.probe(draft())

        assertTrue(result is HermesConnectionResult.Success)
        result as HermesConnectionResult.Success
        assertEquals("0.20-test", result.version)
        assertTrue(result.sessionsSupported)
        assertTrue(result.sessionStreamingSupported)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun bearerHeaderIsSentOnBothRequestsAndNeverInUrls() {
        enqueueValidResponses()

        client.probe(draft())

        val healthRequest = server.takeRequest()
        val capabilitiesRequest = server.takeRequest()
        assertTrue(healthRequest.getHeader("Authorization") == "Bearer $FIXTURE_KEY")
        assertTrue(capabilitiesRequest.getHeader("Authorization") == "Bearer $FIXTURE_KEY")
        assertFalse(healthRequest.requestUrl!!.toString().contains(FIXTURE_KEY))
        assertFalse(capabilitiesRequest.requestUrl!!.toString().contains(FIXTURE_KEY))
        assertEquals(null, healthRequest.requestUrl!!.query)
        assertEquals(null, capabilitiesRequest.requestUrl!!.query)
    }

    @Test
    fun profilePrefixIsPreservedForBothEndpoints() {
        enqueueValidResponses()
        val prefixedBaseUrl = server.url("/p/android").toString().trimEnd('/')

        client.probe(draft(prefixedBaseUrl))

        assertEquals("/p/android/health", server.takeRequest().requestUrl!!.encodedPath)
        assertEquals(
            "/p/android/v1/capabilities",
            server.takeRequest().requestUrl!!.encodedPath
        )
    }

    @Test
    fun malformedHealthIsIncompatible() {
        server.enqueue(MockResponse().setBody("not-json"))

        assertFailure(
            client.probe(draft()),
            ConnectionFailureReason.INCOMPATIBLE_API
        )
    }

    @Test
    fun wrongHealthContractIsIncompatible() {
        server.enqueue(
            jsonResponse(
                JSONObject()
                    .put("status", "ok")
                    .put("platform", "different-platform")
                    .put("version", "0.20-test")
            )
        )

        assertFailure(
            client.probe(draft()),
            ConnectionFailureReason.INCOMPATIBLE_API
        )
    }

    @Test
    fun wrongCapabilitiesContractIsIncompatible() {
        server.enqueue(validHealthResponse())
        val capabilities = validCapabilitiesJson()
        capabilities.getJSONObject("auth").put("required", false)
        server.enqueue(jsonResponse(capabilities))

        assertFailure(
            client.probe(draft()),
            ConnectionFailureReason.INCOMPATIBLE_API
        )
    }

    @Test
    fun unauthorizedAndForbiddenAreInvalidCredentials() {
        listOf(401, 403).forEach { code ->
            server.enqueue(MockResponse().setResponseCode(code))
            assertFailure(
                client.probe(draft()),
                ConnectionFailureReason.INVALID_CREDENTIALS
            )
        }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun rateLimitIsNotRetried() {
        server.enqueue(MockResponse().setResponseCode(429))

        assertFailure(
            client.probe(draft()),
            ConnectionFailureReason.RATE_LIMITED
        )
        assertEquals(1, server.requestCount)
    }

    @Test
    fun unexpectedHttpStatusIsGeneric() {
        server.enqueue(MockResponse().setResponseCode(500))

        assertFailure(client.probe(draft()), ConnectionFailureReason.HTTP)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun readTimeoutIsMappedSeparately() {
        val timeoutClient = trustedClient.newBuilder()
            .readTimeout(100, TimeUnit.MILLISECONDS)
            .callTimeout(300, TimeUnit.MILLISECONDS)
            .build()
        server.enqueue(
            validHealthResponse().setBodyDelay(500, TimeUnit.MILLISECONDS)
        )

        assertFailure(
            HermesConnectionClient(timeoutClient).probe(draft()),
            ConnectionFailureReason.TIMEOUT
        )
    }

    @Test
    fun untrustedCertificateIsTlsFailure() {
        val untrusted = HermesConnectionClient(OkHttpClient.Builder().build())

        assertFailure(
            untrusted.probe(draft()),
            ConnectionFailureReason.TLS
        )
    }

    @Test
    fun redirectIsNotFollowed() {
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .addHeader("Location", server.url("/redirect-target"))
        )
        server.enqueue(validHealthResponse())

        assertFailure(client.probe(draft()), ConnectionFailureReason.HTTP)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun oversizedBodyIsIncompatible() {
        server.enqueue(MockResponse().setBody("a".repeat(256 * 1024 + 1)))

        assertFailure(
            client.probe(draft()),
            ConnectionFailureReason.INCOMPATIBLE_API
        )
    }

    @Test
    fun cleartextAndUrlsWithUnsafeComponentsAreRejectedLocally() {
        val invalidUrls = listOf(
            "http://localhost/",
            "https://user@example.invalid/",
            "https://example.invalid/?query=value",
            "https://example.invalid/#fragment"
        )

        invalidUrls.forEach { url ->
            assertFailure(
                client.probe(draft(url)),
                ConnectionFailureReason.INVALID_INPUT
            )
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun malformedBearerValuesAreRejectedBeforeARequest() {
        listOf("", "fixture credential", "fixture\ncredential").forEach { key ->
            val result = client.probe(
                ProfileDraft(
                    profileName = "Fixture",
                    baseUrl = server.url("/").toString(),
                    apiKey = SecretValue(key)
                )
            )
            assertFailure(result, ConnectionFailureReason.INVALID_INPUT)
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun networkFailureIsMappedWithoutExceptionText() {
        val stoppedServerCertificates = HandshakeCertificates.Builder()
            .heldCertificate(testCertificate)
            .build()
        val stoppedServer = MockWebServer().apply {
            useHttps(stoppedServerCertificates.sslSocketFactory(), false)
            start()
        }
        val unavailableUrl = stoppedServer.url("/").toString()
        stoppedServer.shutdown()

        assertFailure(
            client.probe(draft(unavailableUrl)),
            ConnectionFailureReason.NETWORK
        )
    }

    private fun draft(baseUrl: String = server.url("/").toString()) = ProfileDraft(
        profileName = "Fixture",
        baseUrl = baseUrl,
        apiKey = SecretValue(FIXTURE_KEY)
    )

    private fun enqueueValidResponses() {
        server.enqueue(validHealthResponse())
        server.enqueue(jsonResponse(validCapabilitiesJson()))
    }

    private fun validHealthResponse() = jsonResponse(
        JSONObject()
            .put("status", "ok")
            .put("platform", "hermes-agent")
            .put("version", "0.20-test")
    )

    private fun validCapabilitiesJson() = JSONObject()
        .put("object", "hermes.api_server.capabilities")
        .put("platform", "hermes-agent")
        .put(
            "auth",
            JSONObject()
                .put("type", "bearer")
                .put("required", true)
        )
        .put(
            "runtime",
            JSONObject()
                .put("mode", "server_agent")
                .put("tool_execution", "server")
        )
        .put(
            "features",
            JSONObject()
                .put("session_resources", true)
                .put("session_chat_streaming", true)
        )
        .put(
            "endpoints",
            JSONObject()
                .put(
                    "health",
                    JSONObject().put("method", "GET").put("path", "/health")
                )
                .put(
                    "sessions",
                    JSONObject().put("method", "GET").put("path", "/api/sessions")
                )
                .put(
                    "session_chat_stream",
                    JSONObject()
                        .put("method", "POST")
                        .put("path", "/api/sessions/{session_id}/chat/stream")
                )
        )

    private fun jsonResponse(json: JSONObject) = MockResponse()
        .setResponseCode(200)
        .addHeader("Content-Type", "application/json")
        .setBody(json.toString())

    private fun assertFailure(
        result: HermesConnectionResult,
        expectedReason: ConnectionFailureReason
    ) {
        if (result !is HermesConnectionResult.Failure) {
            fail("Expected HermesConnectionResult.Failure")
        }
        result as HermesConnectionResult.Failure
        assertEquals(expectedReason, result.reason)
        assertFalse(result.toString().contains(FIXTURE_KEY))
    }

    private companion object {
        const val FIXTURE_KEY = "fixture-credential"
    }
}
