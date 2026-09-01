package io.github.excludebarrier.orsikrelay.network

import io.github.excludebarrier.orsikrelay.domain.profile.SecretValue
import io.github.excludebarrier.orsikrelay.domain.session.SessionListError
import io.github.excludebarrier.orsikrelay.domain.session.SessionListResult
import java.math.BigInteger
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HermesSessionClientTest {

    private lateinit var server: MockWebServer
    private lateinit var certificate: HeldCertificate
    private lateinit var trustedClient: OkHttpClient
    private lateinit var client: HermesSessionClient

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
        client = HermesSessionClient(trustedClient)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun successfulRequestPreservesPathQueryHeaderOrderAndPagination() {
        server.enqueue(jsonResponse(validPage()))
        val prefixedBaseUrl = server.url("/p/android").toString().trimEnd('/')

        val result = client.fetchPage(
            baseUrl = prefixedBaseUrl,
            apiKey = SecretValue(FIXTURE_KEY),
            limit = 25,
            offset = 50
        )

        assertTrue(result is SessionListResult.Success)
        val page = (result as SessionListResult.Success).page
        assertEquals(listOf("session-a", "session-b"), page.sessions.map { it.id })
        assertEquals("Erste Session", page.sessions[0].title)
        assertEquals("cli", page.sessions[0].source)
        assertEquals(1_788_000_000L, page.sessions[0].lastActiveEpochSeconds)
        assertEquals(7L, page.sessions[0].messageCount)
        assertTrue(page.sessions[0].pinned)
        assertNull(page.sessions[1].title)
        assertNull(page.sessions[1].source)
        assertNull(page.sessions[1].lastActiveEpochSeconds)
        assertNull(page.sessions[1].messageCount)
        assertFalse(page.sessions[1].pinned)
        assertTrue(page.hasMore)

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/p/android/api/sessions", request.requestUrl!!.encodedPath)
        assertEquals("25", request.requestUrl!!.queryParameter("limit"))
        assertEquals("50", request.requestUrl!!.queryParameter("offset"))
        assertEquals("false", request.requestUrl!!.queryParameter("include_children"))
        assertEquals("Bearer $FIXTURE_KEY", request.getHeader("Authorization"))
        assertFalse(request.requestUrl!!.toString().contains(FIXTURE_KEY))
        assertFalse(result.toString().contains(FIXTURE_KEY))
    }

    @Test
    fun emptyPageIsARealSuccess() {
        val body = JSONObject()
            .put("object", "list")
            .put("data", JSONArray())
            .put("limit", 50)
            .put("offset", 0)
            .put("has_more", false)
        server.enqueue(jsonResponse(body))

        val result = fetch()

        assertTrue(result is SessionListResult.Success)
        val page = (result as SessionListResult.Success).page
        assertTrue(page.sessions.isEmpty())
        assertFalse(page.hasMore)
    }

    @Test
    fun malformedRootAndRequiredFieldsAreRejectedStrictly() {
        val invalidBodies = listOf(
            "not-json",
            JSONObject().put("object", "wrong").put("data", JSONArray())
                .put("limit", 50).put("offset", 0).put("has_more", false).toString(),
            JSONObject().put("object", "list").put("data", "not-array")
                .put("limit", 50).put("offset", 0).put("has_more", false).toString(),
            JSONObject().put("object", "list").put("data", JSONArray())
                .put("limit", -1).put("offset", 0).put("has_more", false).toString(),
            JSONObject().put("object", "list").put("data", JSONArray())
                .put("limit", 50).put("offset", 1.5).put("has_more", false).toString(),
            JSONObject().put("object", "list").put("data", JSONArray())
                .put("limit", 50).put("offset", 0).put("has_more", "false").toString()
        )

        invalidBodies.forEachIndexed { index, body ->
            server.enqueue(MockResponse().setBody(body))
            assertFailure(
                fetch(),
                SessionListError.IncompatibleApi,
                "invalid root fixture $index"
            )
        }
    }

    @Test
    fun sessionIdAndOptionalFieldTypesAreValidatedStrictly() {
        val invalidSessions = listOf(
            JSONObject().put("id", ""),
            JSONObject().put("id", 4),
            JSONObject().put("id", "a").put("title", 4),
            JSONObject().put("id", "a").put("source", false),
            JSONObject().put("id", "a").put("last_active", -1),
            JSONObject().put("id", "a").put("last_active", "7"),
            JSONObject().put("id", "a").put("message_count", -1),
            JSONObject().put("id", "a").put("message_count", "7"),
            JSONObject().put("id", "a").put("pinned", "true")
        )

        invalidSessions.forEachIndexed { index, session ->
            val body = JSONObject()
                .put("object", "list")
                .put("data", JSONArray().put(session))
                .put("limit", 50)
                .put("offset", 0)
                .put("has_more", false)
            server.enqueue(jsonResponse(body))
            assertFailure(
                fetch(),
                SessionListError.IncompatibleApi,
                "invalid session fixture $index"
            )
        }
    }

    @Test
    fun numericFieldsOutsideLongRangeAreRejectedWithoutWraparound() {
        val tooLarge = BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE)
        val invalidBodies = listOf(
            JSONObject()
                .put("object", "list")
                .put("data", JSONArray())
                .put("limit", tooLarge)
                .put("offset", 0)
                .put("has_more", false),
            JSONObject()
                .put("object", "list")
                .put(
                    "data",
                    JSONArray().put(
                        JSONObject().put("id", "session-a").put("last_active", tooLarge)
                    )
                )
                .put("limit", 50)
                .put("offset", 0)
                .put("has_more", false),
            JSONObject()
                .put("object", "list")
                .put(
                    "data",
                    JSONArray().put(
                        JSONObject().put("id", "session-a").put("message_count", tooLarge)
                    )
                )
                .put("limit", 50)
                .put("offset", 0)
                .put("has_more", false)
        )

        invalidBodies.forEach { body ->
            server.enqueue(jsonResponse(body))
            assertFailure(fetch(), SessionListError.IncompatibleApi)
        }
    }

    @Test
    fun duplicateSessionIdsAreRejectedBeforeTheyReachLazyColumnKeys() {
        val body = JSONObject()
            .put("object", "list")
            .put(
                "data",
                JSONArray()
                    .put(JSONObject().put("id", "duplicate-id"))
                    .put(JSONObject().put("id", "duplicate-id"))
            )
            .put("limit", 50)
            .put("offset", 0)
            .put("has_more", false)
        server.enqueue(jsonResponse(body))

        assertFailure(fetch(), SessionListError.IncompatibleApi)
    }

    @Test
    fun explicitNullOptionalFieldsAreAcceptedAsAbsent() {
        val body = validPage()
        body.getJSONArray("data").put(
            JSONObject()
                .put("id", "session-c")
                .put("title", JSONObject.NULL)
                .put("source", JSONObject.NULL)
                .put("last_active", JSONObject.NULL)
                .put("message_count", JSONObject.NULL)
                .put("pinned", JSONObject.NULL)
        )
        server.enqueue(jsonResponse(body))

        val result = fetch() as SessionListResult.Success
        val session = result.page.sessions.last()

        assertNull(session.title)
        assertNull(session.source)
        assertNull(session.lastActiveEpochSeconds)
        assertNull(session.messageCount)
        assertFalse(session.pinned)
    }

    @Test
    fun finiteFractionalLastActiveIsAcceptedAndFlooredToWholeSeconds() {
        val body = JSONObject()
            .put("object", "list")
            .put(
                "data",
                JSONArray()
                    .put(JSONObject().put("id", "session-a").put("last_active", 1_788_000_000.75))
                    .put(JSONObject().put("id", "session-b").put("last_active", 0.5))
                    .put(JSONObject().put("id", "session-c").put("last_active", 1788000000.0))
            )
            .put("limit", 50)
            .put("offset", 0)
            .put("has_more", false)
        server.enqueue(jsonResponse(body))

        val result = fetch() as SessionListResult.Success
        assertEquals(
            listOf(1_788_000_000L, 0L, 1788000000L),
            result.page.sessions.map { it.lastActiveEpochSeconds }
        )
    }

    @Test
    fun authenticationRateLimitAndHttpErrorsAreClosedAndNotRetried() {
        listOf(
            401 to SessionListError.InvalidCredentials,
            403 to SessionListError.InvalidCredentials,
            429 to SessionListError.RateLimited,
            500 to SessionListError.Http
        ).forEach { (code, reason) ->
            server.enqueue(MockResponse().setResponseCode(code))
            assertFailure(fetch(), reason)
        }
        assertEquals(4, server.requestCount)
    }

    @Test
    fun redirectIsNotFollowed() {
        server.enqueue(
            MockResponse().setResponseCode(302)
                .addHeader("Location", server.url("/redirect-target"))
        )
        server.enqueue(jsonResponse(validPage()))

        assertFailure(fetch(), SessionListError.Http)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun declaredAndStreamedOversizedBodiesAreRejected() {
        val oversized = "a".repeat(512 * 1024 + 1)
        server.enqueue(MockResponse().setBody(oversized))
        assertFailure(fetch(), SessionListError.IncompatibleApi)

        server.enqueue(MockResponse().setChunkedBody(oversized, 1024))
        assertFailure(fetch(), SessionListError.IncompatibleApi)
    }

    @Test
    fun timeoutTlsAndNetworkFailuresAreMappedWithoutDetails() {
        val timeoutClient = trustedClient.newBuilder()
            .readTimeout(100, TimeUnit.MILLISECONDS)
            .callTimeout(300, TimeUnit.MILLISECONDS)
            .build()
        server.enqueue(jsonResponse(validPage()).setBodyDelay(500, TimeUnit.MILLISECONDS))
        assertFailure(
            HermesSessionClient(timeoutClient).fetchPage(
                baseUrl(), SecretValue(FIXTURE_KEY), 50, 0
            ),
            SessionListError.Timeout
        )

        val untrusted = HermesSessionClient(OkHttpClient.Builder().build())
        assertFailure(
            untrusted.fetchPage(baseUrl(), SecretValue(FIXTURE_KEY), 50, 0),
            SessionListError.Tls
        )

        val stopped = MockWebServer().apply { start() }
        val unavailable = stopped.url("/").toString().replace("http://", "https://")
        stopped.shutdown()
        val networkResult = client.fetchPage(
            unavailable, SecretValue(FIXTURE_KEY), 50, 0
        )
        assertTrue(networkResult is SessionListResult.Failure)
        assertTrue(
            (networkResult as SessionListResult.Failure).error in
                setOf(SessionListError.Network, SessionListError.Tls)
        )
        assertFalse(networkResult.toString().contains(unavailable))
        assertFalse(networkResult.toString().contains(FIXTURE_KEY))
    }

    @Test
    fun unsafeLocalInputsCreateNoRequest() {
        val invalidCalls = listOf(
            Triple("http://example.invalid", SecretValue(FIXTURE_KEY), 50 to 0),
            Triple("https://user@example.invalid", SecretValue(FIXTURE_KEY), 50 to 0),
            Triple(baseUrl(), SecretValue(""), 50 to 0),
            Triple(baseUrl(), SecretValue("fixture credential"), 50 to 0),
            Triple(baseUrl(), SecretValue("fixture\ncredential"), 50 to 0),
            Triple(baseUrl(), SecretValue(FIXTURE_KEY), 0 to 0),
            Triple(baseUrl(), SecretValue(FIXTURE_KEY), 51 to 0),
            Triple(baseUrl(), SecretValue(FIXTURE_KEY), 50 to -1)
        )

        invalidCalls.forEach { (url, key, page) ->
            assertFailure(
                client.fetchPage(url, key, page.first, page.second),
                SessionListError.InvalidInput
            )
        }
        assertEquals(0, server.requestCount)
    }

    private fun fetch(): SessionListResult = client.fetchPage(
        baseUrl = baseUrl(),
        apiKey = SecretValue(FIXTURE_KEY),
        limit = 50,
        offset = 0
    )

    private fun baseUrl(): String = server.url("/").toString().trimEnd('/')

    private fun validPage(): JSONObject = JSONObject()
        .put("object", "list")
        .put(
            "data",
            JSONArray()
                .put(
                    JSONObject()
                        .put("id", "session-a")
                        .put("title", "Erste Session")
                        .put("source", "cli")
                        .put("last_active", 1_788_000_000L)
                        .put("message_count", 7)
                        .put("pinned", true)
                )
                .put(JSONObject().put("id", "session-b"))
        )
        .put("limit", 25)
        .put("offset", 50)
        .put("has_more", true)

    private fun jsonResponse(body: JSONObject): MockResponse = MockResponse()
        .addHeader("Content-Type", "application/json")
        .setBody(body.toString())

    private fun assertFailure(
        result: SessionListResult,
        expected: SessionListError,
        message: String = "expected closed failure"
    ) {
        assertTrue(message, result is SessionListResult.Failure)
        assertEquals(expected, (result as SessionListResult.Failure).error)
        assertFalse(result.toString().contains(FIXTURE_KEY))
    }

    private companion object {
        const val FIXTURE_KEY = "fixture-credential"
    }
}
