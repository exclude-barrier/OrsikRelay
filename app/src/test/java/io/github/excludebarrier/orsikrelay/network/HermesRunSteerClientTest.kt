package io.github.excludebarrier.orsikrelay.network

import io.github.excludebarrier.orsikrelay.domain.profile.SecretValue
import io.github.excludebarrier.orsikrelay.domain.run.RunSteerError
import io.github.excludebarrier.orsikrelay.domain.run.RunSteerResult
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Protocol
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

/**
 * Mission 008A: transport contract of the single authenticated
 * `POST /v1/runs/{run_id}/steer` write. The expected wire format was
 * verified read-only against the installed server contract: the client
 * sends the canonical JSON body `{"input":"<text>"}` with a Bearer key
 * and no session headers; the accepted answer is
 * `{"object":"hermes.run.steer","run_id":"<exactly requested>","accepted":true}`
 * which only means queued/accepted (the run stays active); 409 means
 * `run_not_accepting_steer` or `steer_not_accepted` and is never
 * resubmitted automatically.
 */
class HermesRunSteerClientTest {

    private lateinit var server: MockWebServer
    private lateinit var certificate: HeldCertificate
    private lateinit var trustedClient: OkHttpClient
    private lateinit var client: HermesRunSteerClient

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
        // Pin the fixture to HTTP/1.1 for the same reason the
        // streaming test does (MockWebServer h2 framing corruption).
        trustedClient = OkHttpClient.Builder()
            .sslSocketFactory(
                clientCertificates.sslSocketFactory(),
                clientCertificates.trustManager
            )
            .protocols(listOf(Protocol.HTTP_1_1))
            .build()
        client = HermesRunSteerClient(trustedClient)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun queuedSteerSendsExactPostPathEncodedRunIdBearerJsonBodyAndNoSessionHeaders() {
        server.enqueue(jsonResponse(queuedBody(ENCODED_RUN_ID)))

        val result = client.steerRun(baseUrl(), SecretValue(FIXTURE_KEY), ENCODED_RUN_ID, STEER_TEXT)

        assertEquals(RunSteerResult.Queued(ENCODED_RUN_ID), result)
        val request = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) {
            "expected one recorded request"
        }
        assertEquals("POST", request.method)
        // Existing base-URL path prefix must be preserved and the run
        // ID must be exactly one encoded path segment: '/' -> %2F,
        // space -> %20; no slash may remain inside the segment.
        assertEquals(
            "/p/android/v1/runs/synthetic%2Frun%20stop/steer",
            request.requestUrl!!.encodedPath
        )
        assertNull(request.requestUrl!!.query)
        assertEquals("Bearer $FIXTURE_KEY", request.getHeader("Authorization"))
        assertEquals("application/json", request.getHeader("Content-Type"))
        // Canonical JSON body: exactly the trimmed text, nothing else.
        assertEquals("""{"input":"steer the run"}""", request.body.readUtf8())
        // The steer contract uses the bearer key only; no session
        // headers of any kind may be sent.
        assertNull(request.getHeader("X-Hermes-Session-Id"))
        assertNull(request.getHeader("X-Hermes-Session-Key"))
        assertFalse(request.requestUrl!!.toString().contains(FIXTURE_KEY))
    }

    @Test
    fun edgeWhitespaceIsTrimmedBeforeTheWireAndBlankTextIsRejectedLocally() {
        // Leading and trailing whitespace (including newlines) is
        // trimmed; inner whitespace is preserved on the wire.
        server.enqueue(jsonResponse(queuedBody(RUN_ID)))

        val result = client.steerRun(
            baseUrl(),
            SecretValue(FIXTURE_KEY),
            RUN_ID,
            "  lead\nand trail  "
        )

        assertEquals(RunSteerResult.Queued(RUN_ID), result)
        val request = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) {
            "expected one recorded request"
        }
        // \n appears on the wire as the two-character JSON escape.
        assertEquals("""{"input":"lead\nand trail"}""", request.body.readUtf8())

        // Blank text is a local input failure: nothing goes on the wire.
        listOf("", "   ", "\n\t ", " \r\n ").forEach { text ->
            assertEquals(
                "blank text must fail closed locally",
                RunSteerResult.Failure(RunSteerError.InvalidInput),
                client.steerRun(baseUrl(), SecretValue(FIXTURE_KEY), RUN_ID, text)
            )
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun normalMultiLineTextIncludingCrLfStaysAllowed() {
        // Line breaks are normal text content, not protocol control:
        // LF and CRLF line endings must both be accepted and preserved
        // inside the JSON string.
        listOf(
            "line one\nline two",
            "line one\r\nline two",
            "tab\tinside\nand more"
        ).forEach { text ->
            server.enqueue(jsonResponse(queuedBody(RUN_ID)))
            assertEquals(
                "text must be accepted",
                RunSteerResult.Queued(RUN_ID),
                client.steerRun(baseUrl(), SecretValue(FIXTURE_KEY), RUN_ID, text)
            )
        }
        val first = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertEquals("""{"input":"line one\nline two"}""", first.body.readUtf8())
        val second = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertEquals("""{"input":"line one\r\nline two"}""", second.body.readUtf8())
        assertEquals(3, server.requestCount)
    }

    @Test
    fun httpStatusesMapToClosedOutcomesWithoutRetry() {
        // 409 is the closed NotAccepting outcome, not a failure: a later
        // UI may deliberately offer to send the text as a new message,
        // never automatically.
        listOf(
            409 to RunSteerResult.NotAccepting(RUN_ID),
            401 to RunSteerResult.Failure(RunSteerError.InvalidCredentials),
            403 to RunSteerResult.Failure(RunSteerError.InvalidCredentials),
            404 to RunSteerResult.Failure(RunSteerError.NotFound),
            429 to RunSteerResult.Failure(RunSteerError.RateLimited),
            500 to RunSteerResult.Failure(RunSteerError.Http),
            503 to RunSteerResult.Failure(RunSteerError.Http)
        ).forEach { (code, expected) ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(code)
                    .setBody(
                        """{"error":{"message":"dynamic server detail $code"}}"""
                    )
            )
            assertEquals(expected, steer())
        }
        // One request per attempt: the steer POST is never retried.
        assertEquals(7, server.requestCount)
    }

    @Test
    fun incompatibleSuccessAnswersAreIncompatibleApi() {
        listOf(
            // Wrong object discriminator.
            """{"object":"hermes.run.stop","run_id":"$RUN_ID","accepted":true}""",
            // Missing object.
            """{"run_id":"$RUN_ID","accepted":true}""",
            // Wrong object type.
            """{"object":12,"run_id":"$RUN_ID","accepted":true}""",
            // Missing or wrong-typed run_id.
            """{"object":"hermes.run.steer","accepted":true}""",
            """{"object":"hermes.run.steer","run_id":42,"accepted":true}""",
            // Missing accepted, false, or wrong-typed accepted.
            """{"object":"hermes.run.steer","run_id":"$RUN_ID"}""",
            """{"object":"hermes.run.steer","run_id":"$RUN_ID","accepted":false}""",
            """{"object":"hermes.run.steer","run_id":"$RUN_ID","accepted":"true"}""",
            """{"object":"hermes.run.steer","run_id":"$RUN_ID","accepted":1}""",
            // Invalid JSON / empty / wrong root type.
            "not json",
            "",
            "}{",
            """["$RUN_ID", true]""",
            "null"
        ).forEach { body ->
            server.enqueue(jsonResponse(body))
            assertEquals(
                "body $body must be rejected",
                RunSteerResult.Failure(RunSteerError.IncompatibleApi),
                steer()
            )
        }
    }

    @Test
    fun runIdMismatchIsIncompatibleApi() {
        server.enqueue(jsonResponse(queuedBody("other_run_id")))

        assertEquals(
            RunSteerResult.Failure(RunSteerError.IncompatibleApi),
            steer()
        )
    }

    @Test
    fun extraServerFieldsAreIgnoredAndQueuedIsReturned() {
        // The server may add informational fields; only the closed
        // contract fields are read, everything else is ignored.
        val body =
            "{\"object\":\"hermes.run.steer\",\"run_id\":\"$RUN_ID\",\"accepted\":true," +
                "\"queued_at\":1000.0,\"session_id\":\"synthetic-session\"}"
        server.enqueue(jsonResponse(body))

        assertEquals(RunSteerResult.Queued(RUN_ID), steer())
    }

    @Test
    fun invalidJsonAndOversizedBodyAreIncompatibleApi() {
        listOf("not json", "", "}{").forEach { body ->
            server.enqueue(jsonResponse(body))
            assertEquals(
                "body $body must be rejected",
                RunSteerResult.Failure(RunSteerError.IncompatibleApi),
                steer()
            )
        }
        // Well above the client's closed response size limit.
        val oversized = JSONObject()
            .put("object", "hermes.run.steer")
            .put("run_id", RUN_ID)
            .put("accepted", true)
            .put("padding", "x".repeat(600 * 1024))
            .toString()
        server.enqueue(jsonResponse(oversized))
        assertEquals(
            RunSteerResult.Failure(RunSteerError.IncompatibleApi),
            steer()
        )
    }

    @Test
    fun wrongContentTypeIsIncompatibleApiButJsonParametersAreAllowed() {
        listOf("text/html", "application/xml", "text/event-stream", null).forEach { contentType ->
            server.enqueue(
                MockResponse()
                    .apply { if (contentType != null) addHeader("Content-Type", contentType) }
                    .setBody(queuedBody(RUN_ID))
            )
            assertEquals(
                "content type $contentType must be rejected",
                RunSteerResult.Failure(RunSteerError.IncompatibleApi),
                steer()
            )
        }
        // A JSON media type with parameters is still JSON.
        server.enqueue(
            MockResponse()
                .addHeader("Content-Type", "application/json; charset=utf-8")
                .setBody(queuedBody(RUN_ID))
        )
        assertEquals(RunSteerResult.Queued(RUN_ID), steer())
    }

    @Test
    fun serverErrorTextRunIdKeyAndSteerTextNeverReachTheResult() {
        server.enqueue(
            MockResponse()
                .setResponseCode(404)
                .setBody(
                    """{"error":{"message":"Run not found: $RUN_ID, key $FIXTURE_KEY, text $STEER_TEXT"}}"""
                )
        )

        val result = steer()

        assertEquals(RunSteerResult.Failure(RunSteerError.NotFound), result)
        assertFalse(result.toString().contains("Run not found"))
        assertFalse(result.toString().contains(FIXTURE_KEY))
        assertFalse(result.toString().contains(STEER_TEXT))
    }

    @Test
    fun redirectIsNotFollowedAndMapsToHttp() {
        server.enqueue(
            MockResponse().setResponseCode(302)
                .addHeader("Location", server.url("/redirect-target"))
        )
        server.enqueue(jsonResponse(queuedBody(RUN_ID)))

        val result = steer()

        assertEquals(RunSteerResult.Failure(RunSteerError.Http), result)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun tlsFailureMapsToClosedTlsErrorWithoutLeakingKeyOrUrl() {
        server.enqueue(jsonResponse(queuedBody(RUN_ID)))
        val untrusted = HermesRunSteerClient(OkHttpClient.Builder().build())

        val result = untrusted.steerRun(baseUrl(), SecretValue(FIXTURE_KEY), RUN_ID, STEER_TEXT)

        assertEquals(RunSteerResult.Failure(RunSteerError.Tls), result)
        assertFalse(result.toString().contains(FIXTURE_KEY))
    }

    @Test
    fun timeoutMapsToClosedTimeoutError() {
        val shortClient = trustedClient.newBuilder()
            .readTimeout(100, TimeUnit.MILLISECONDS)
            .callTimeout(300, TimeUnit.MILLISECONDS)
            .build()
        server.enqueue(
            jsonResponse(queuedBody(RUN_ID))
                .setBodyDelay(2000, TimeUnit.MILLISECONDS)
        )

        val result = HermesRunSteerClient(shortClient)
            .steerRun(baseUrl(), SecretValue(FIXTURE_KEY), RUN_ID, STEER_TEXT)

        assertEquals(RunSteerResult.Failure(RunSteerError.Timeout), result)
    }

    @Test
    fun unreachableServerMapsToClosedNetworkError() {
        val stopped = MockWebServer().apply { start() }
        val unavailable = stopped.url("/").toString().replace("http://", "https://")
        stopped.shutdown()

        val result = client.steerRun(unavailable, SecretValue(FIXTURE_KEY), RUN_ID, STEER_TEXT)

        assertTrue(
            "expected a closed transport failure, got $result",
            result == RunSteerResult.Failure(RunSteerError.Network) ||
                result == RunSteerResult.Failure(RunSteerError.Tls)
        )
        assertFalse(result.toString().contains(FIXTURE_KEY))
        assertFalse(result.toString().contains(unavailable))
    }

    @Test
    fun localValidationFailuresSendNoRequest() {
        listOf(
            "http://example.invalid" to "http-url",
            "not-a-url" to "bad-url",
            baseUrl() to "blank-key",
            baseUrl() to "whitespace-key",
            baseUrl() to "control-char-key",
            baseUrl() to "overlong-key",
            baseUrl() to "control-char-run-id",
            baseUrl() to "overlong-run-id",
            baseUrl() to "blank-run-id",
            baseUrl() to "overlong-text",
            baseUrl() to "control-char-text",
            baseUrl() to "nul-text"
        ).forEach { (url, label) ->
            val key = when (label) {
                "blank-key" -> ""
                "whitespace-key" -> "fix ture"
                "control-char-key" -> "fix\u0001ture"
                "overlong-key" -> "k".repeat(MAX_API_KEY_LIMIT + 1)
                else -> FIXTURE_KEY
            }
            val runId = when (label) {
                "control-char-run-id" -> "run\u0001synthetic"
                "overlong-run-id" -> "r".repeat(257)
                "blank-run-id" -> "   "
                else -> RUN_ID
            }
            val text = when (label) {
                "overlong-text" -> "x".repeat(MAX_STEER_TEXT_LIMIT + 1)
                "control-char-text" -> "steer\u0007text"
                "nul-text" -> "steer\u0000text"
                else -> STEER_TEXT
            }
            val result = client.steerRun(url, SecretValue(key), runId, text)
            assertEquals(
                "$label must fail closed locally",
                RunSteerResult.Failure(RunSteerError.InvalidInput),
                result
            )
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun keyNeverAppearsInUrlOrResultAndQueuedCarriesNoSteerText() {
        server.enqueue(jsonResponse(queuedBody(RUN_ID)))

        val result = client.steerRun(baseUrl(), SecretValue(FIXTURE_KEY), RUN_ID, STEER_TEXT)

        val request = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) {
            "expected one recorded request"
        }
        assertFalse(request.requestUrl!!.toString().contains(FIXTURE_KEY))
        // The result is closed: it carries the requested run ID only,
        // never the key and never the steer text.
        assertFalse(result.toString().contains(FIXTURE_KEY))
        assertFalse(result.toString().contains(STEER_TEXT))
    }

    private fun steer(): RunSteerResult =
        client.steerRun(baseUrl(), SecretValue(FIXTURE_KEY), RUN_ID, STEER_TEXT)

    private fun baseUrl(): String =
        server.url("/p/android").toString().trimEnd('/')

    private fun jsonResponse(body: String): MockResponse = MockResponse()
        .addHeader("Content-Type", "application/json")
        .setBody(body)

    private fun queuedBody(runId: String): String =
        """{"object":"hermes.run.steer","run_id":"$runId","accepted":true}"""

    private companion object {
        const val FIXTURE_KEY = "fixture-credential"
        const val RUN_ID = "run_synthetic"
        const val ENCODED_RUN_ID = "synthetic/run stop"
        const val STEER_TEXT = "steer the run"
        // Mirrors the client's closed limits so the tests can build
        // exactly one character beyond them.
        const val MAX_API_KEY_LIMIT = 4096
        const val MAX_STEER_TEXT_LIMIT = 32_768
    }
}
