package io.github.excludebarrier.orsikrelay.network

import io.github.excludebarrier.orsikrelay.domain.profile.SecretValue
import io.github.excludebarrier.orsikrelay.domain.session.SessionCreateError
import io.github.excludebarrier.orsikrelay.domain.session.SessionCreateResult
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HermesSessionCreateClientTest {

    private lateinit var server: MockWebServer
    private lateinit var certificate: HeldCertificate
    private lateinit var trustedClient: OkHttpClient
    private lateinit var client: HermesSessionCreateClient

    @Before
    fun setUp() {
        certificate = HeldCertificate.Builder()
            .addSubjectAlternativeName("localhost")
            .build()
        val serverCertificates = HandshakeCertificates.Builder()
            .heldCertificate(certificate)
            .build()
        val clientCertificates = HandshakeCertificates.Builder()
            .addTrustedCertificate(certificate.certificate)
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
        client = HermesSessionCreateClient(trustedClient)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun successfulRequestSendsExactPathBearerHeaderAndContentType() {
        server.enqueue(createdResponse(createdBody()))
        val prefixedBaseUrl = server.url("/p/android").toString().trimEnd('/')

        val result = client.createSession(
            baseUrl = prefixedBaseUrl,
            apiKey = SecretValue(FIXTURE_KEY),
            title = null
        )

        assertSuccess(result, CREATED_ID, null)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/p/android/api/sessions", request.requestUrl!!.encodedPath)
        assertNull(request.requestUrl!!.query)
        assertEquals("Bearer $FIXTURE_KEY", request.getHeader("Authorization"))
        assertEquals("application/json", request.getHeader("Content-Type"))
        assertEquals("{}", request.body.readUtf8())
        assertFalse(request.requestUrl!!.toString().contains(FIXTURE_KEY))
        assertFalse(result.toString().contains(FIXTURE_KEY))
    }

    @Test
    fun emptyAndWhitespaceTitlesSendEmptyObjectOnly() {
        listOf("", "   ", "\t\n ").forEach { rawTitle ->
            server.enqueue(createdResponse(createdBody()))
            val result = client.createSession(
                baseUrl = baseUrl(),
                apiKey = SecretValue(FIXTURE_KEY),
                title = rawTitle
            )
            assertSuccess(result, CREATED_ID, null)
            val request = server.takeRequest()
            assertEquals("{}", request.body.readUtf8())
        }
        assertEquals(3, server.requestCount)
    }

    @Test
    fun nonBlankTitleIsTrimmedAndSentAsSoleField() {
        server.enqueue(
            createdResponse(createdBody(sessionObject().put("title", "Synthetischer Titel")))
        )

        val result = client.createSession(
            baseUrl = baseUrl(),
            apiKey = SecretValue(FIXTURE_KEY),
            title = "  Synthetischer Titel  "
        )

        assertSuccess(result, CREATED_ID, "Synthetischer Titel")
        val request = server.takeRequest()
        val body = JSONObject(request.body.readUtf8())
        assertEquals(1, body.length())
        assertEquals("Synthetischer Titel", body.getString("title"))
        assertFalse(result.toString().contains(FIXTURE_KEY))
    }

    @Test
    fun invalidBaseUrlAndInvalidKeyCreateNoRequest() {
        val invalidCalls = listOf(
            Triple("http://example.invalid", FIXTURE_KEY, null),
            Triple("https://user@example.invalid", FIXTURE_KEY, null),
            Triple(baseUrl(), "", null),
            Triple(baseUrl(), "fixture credential", null),
            Triple(baseUrl(), "fixture\ncredential", null),
            Triple("not-a-url", FIXTURE_KEY, null)
        )
        invalidCalls.forEach { (url, key, title) ->
            assertFailure(
                client.createSession(url, SecretValue(key), title),
                SessionCreateError.InvalidInput
            )
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun invalidTitleCreatesNoRequest() {
        listOf("x".repeat(513), "title\nwith-control").forEach { title ->
            assertFailure(
                client.createSession(baseUrl(), SecretValue(FIXTURE_KEY), title),
                SessionCreateError.InvalidInput
            )
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun onlyHttp201IsASuccess() {
        listOf(200, 202, 301).forEach { code ->
            server.enqueue(createdResponse(createdBody(), code))
            assertFailure(
                client.createSession(baseUrl(), SecretValue(FIXTURE_KEY), null),
                SessionCreateError.Http
            )
        }
        assertEquals(3, server.requestCount)
    }

    @Test
    fun rootMarkerAndSessionShapeAreValidatedStrictly() {
        val invalidBodies = listOf(
            "not-json",
            "[]",
            JSONObject().put("object", "hermes.session"),
            JSONObject().put("object", "hermes.sessions").put("session", sessionObject()),
            JSONObject().put("object", 4).put("session", sessionObject()),
            createdBody().put("session", "not-an-object").toString(),
            createdBody().put("session", JSONObject()).toString()
        )
        invalidBodies.forEachIndexed { index, body ->
            server.enqueue(createdResponse(body))
            assertFailure(
                client.createSession(baseUrl(), SecretValue(FIXTURE_KEY), null),
                SessionCreateError.IncompatibleApi,
                "invalid root fixture $index"
            )
        }
    }

    @Test
    fun sessionIdIsValidatedStrictly() {
        val invalidBodies = listOf(
            createdBody().put("session", JSONObject().put("id", "")).toString(),
            createdBody().put("session", JSONObject().put("id", "x".repeat(257))).toString(),
            createdBody().put("session", JSONObject().put("id", "id\nwith-control")).toString(),
            createdBody().put("session", JSONObject().put("id", 4)).toString(),
            createdBody().put("session", JSONObject().put("id", JSONObject.NULL)).toString()
        )
        invalidBodies.forEachIndexed { index, body ->
            server.enqueue(createdResponse(body))
            assertFailure(
                client.createSession(baseUrl(), SecretValue(FIXTURE_KEY), null),
                SessionCreateError.IncompatibleApi,
                "invalid id fixture $index"
            )
        }
    }

    @Test
    fun sessionIdAtMaximumLengthWithoutControlCharactersIsAccepted() {
        val maximumId = "x".repeat(256)
        server.enqueue(createdResponse(createdBody(JSONObject().put("id", maximumId))))

        val result = client.createSession(baseUrl(), SecretValue(FIXTURE_KEY), null)

        assertSuccess(result, maximumId, null)
    }

    @Test
    fun echoedTitleMayBeMissingNullOrString() {
        server.enqueue(createdResponse(createdBody()))
        assertSuccess(client.createSession(baseUrl(), SecretValue(FIXTURE_KEY), null), CREATED_ID, null)

        server.enqueue(createdResponse(createdBody().put("session", sessionObject().put("title", JSONObject.NULL))))
        assertSuccess(client.createSession(baseUrl(), SecretValue(FIXTURE_KEY), null), CREATED_ID, null)

        server.enqueue(
            createdResponse(createdBody(sessionObject().put("title", "Synthetischer Titel")))
        )
        assertSuccess(client.createSession(baseUrl(), SecretValue(FIXTURE_KEY), null), CREATED_ID, "Synthetischer Titel")
    }

    @Test
    fun nonStringEchoedTitleIsIncompatible() {
        listOf(4, false, org.json.JSONArray()).forEachIndexed { index, title ->
            val session = sessionObject().put("title", title)
            server.enqueue(createdResponse(createdBody(session)))
            assertFailure(
                client.createSession(baseUrl(), SecretValue(FIXTURE_KEY), null),
                SessionCreateError.IncompatibleApi,
                "invalid title fixture $index"
            )
        }
    }

    @Test
    fun overlongOrControlCharacterEchoedTitleIsIncompatible() {
        listOf("x".repeat(513), "title\nwith-control").forEach { title ->
            server.enqueue(createdResponse(createdBody(sessionObject().put("title", title))))
            assertFailure(
                client.createSession(baseUrl(), SecretValue(FIXTURE_KEY), null),
                SessionCreateError.IncompatibleApi
            )
        }
    }

    @Test
    fun otherClientSafeServerFieldsAreIgnored() {
        val body = createdBody().put("session", sessionObject().put("preview", "hidden-preview"))
        server.enqueue(createdResponse(body))

        val result = client.createSession(baseUrl(), SecretValue(FIXTURE_KEY), null)

        assertSuccess(result, CREATED_ID, null)
        assertFalse(result.toString().contains("hidden-preview"))
    }

    @Test
    fun closedErrorMappingNeverRepeatsTheRequest() {
        listOf(
            400 to SessionCreateError.InvalidTitle,
            401 to SessionCreateError.InvalidCredentials,
            403 to SessionCreateError.InvalidCredentials,
            409 to SessionCreateError.Conflict,
            429 to SessionCreateError.RateLimited,
            500 to SessionCreateError.Http,
            503 to SessionCreateError.Http
        ).forEach { (code, reason) ->
            server.enqueue(MockResponse().setResponseCode(code))
            assertFailure(
                client.createSession(baseUrl(), SecretValue(FIXTURE_KEY), null),
                reason
            )
        }
        assertEquals(7, server.requestCount)
    }

    @Test
    fun redirectIsNotFollowed() {
        server.enqueue(
            MockResponse().setResponseCode(302)
                .addHeader("Location", server.url("/redirect-target"))
        )
        server.enqueue(createdResponse(createdBody()))

        assertFailure(
            client.createSession(baseUrl(), SecretValue(FIXTURE_KEY), null),
            SessionCreateError.Http
        )
        assertEquals(1, server.requestCount)
    }

    @Test
    fun declaredAndStreamedOversizedBodiesAreRejected() {
        val oversized = "a".repeat(512 * 1024 + 1)
        server.enqueue(MockResponse().setBody(oversized).setResponseCode(201))
        assertFailure(
            client.createSession(baseUrl(), SecretValue(FIXTURE_KEY), null),
            SessionCreateError.IncompatibleApi
        )

        server.enqueue(MockResponse().setChunkedBody(oversized, 1024).setResponseCode(201))
        assertFailure(
            client.createSession(baseUrl(), SecretValue(FIXTURE_KEY), null),
            SessionCreateError.IncompatibleApi
        )
    }

    @Test
    fun timeoutTlsAndNetworkFailuresAreMappedWithoutDetails() {
        val timeoutClient = trustedClient.newBuilder()
            .readTimeout(100, TimeUnit.MILLISECONDS)
            .callTimeout(300, TimeUnit.MILLISECONDS)
            .build()
        server.enqueue(createdResponse(createdBody()).setBodyDelay(500, TimeUnit.MILLISECONDS))
        assertFailure(
            HermesSessionCreateClient(timeoutClient)
                .createSession(baseUrl(), SecretValue(FIXTURE_KEY), null),
            SessionCreateError.Timeout
        )

        val untrusted = HermesSessionCreateClient(OkHttpClient.Builder().build())
        assertFailure(
            untrusted.createSession(baseUrl(), SecretValue(FIXTURE_KEY), null),
            SessionCreateError.Tls
        )

        val stopped = MockWebServer().apply { start() }
        val unavailable = stopped.url("/").toString().replace("http://", "https://")
        stopped.shutdown()
        val networkResult = client.createSession(
            unavailable, SecretValue(FIXTURE_KEY), null
        )
        assertTrue(networkResult is SessionCreateResult.Failure)
        assertTrue(
            (networkResult as SessionCreateResult.Failure).error in
                setOf(SessionCreateError.Network, SessionCreateError.Tls)
        )
        assertFalse(networkResult.toString().contains(unavailable))
        assertFalse(networkResult.toString().contains(FIXTURE_KEY))
    }

    @Test
    fun keyNeverAppearsInUrlBodyResultOrErrors() {
        server.enqueue(createdResponse(createdBody()))
        val result = client.createSession(
            baseUrl = baseUrl(),
            apiKey = SecretValue(FIXTURE_KEY),
            title = "Synthetischer Titel"
        )
        val request = server.takeRequest()
        assertFalse(request.requestUrl!!.toString().contains(FIXTURE_KEY))
        assertFalse(request.body.readUtf8().contains(FIXTURE_KEY))
        assertFalse(result.toString().contains(FIXTURE_KEY))

        server.enqueue(MockResponse().setResponseCode(401))
        val failure = client.createSession(baseUrl(), SecretValue(FIXTURE_KEY), null)
        assertFailure(failure, SessionCreateError.InvalidCredentials)
        assertFalse(failure.toString().contains(FIXTURE_KEY))
    }

    private fun baseUrl(): String = server.url("/").toString().trimEnd('/')

    private fun createdBody(session: JSONObject = sessionObject()): JSONObject =
        JSONObject().put("object", "hermes.session").put("session", session)

    private fun sessionObject(): JSONObject = JSONObject().put("id", CREATED_ID)

    private fun createdResponse(body: Any, code: Int = 201): MockResponse =
        MockResponse()
            .setResponseCode(code)
            .addHeader("Content-Type", "application/json")
            .setBody(body.toString())

    private fun assertSuccess(result: SessionCreateResult, sessionId: String, title: String?) {
        assertTrue(result is SessionCreateResult.Success)
        val success = result as SessionCreateResult.Success
        assertEquals(sessionId, success.sessionId)
        assertEquals(title, success.title)
        assertFalse(result.toString().contains(FIXTURE_KEY))
    }

    private fun assertFailure(
        result: SessionCreateResult,
        expected: SessionCreateError,
        message: String = "expected closed failure"
    ) {
        assertTrue(message, result is SessionCreateResult.Failure)
        assertEquals(expected, (result as SessionCreateResult.Failure).error)
        assertFalse(result.toString().contains(FIXTURE_KEY))
    }

    private companion object {
        const val FIXTURE_KEY = "fixture-credential"
        const val CREATED_ID = "synthetic-created-id"
    }
}
