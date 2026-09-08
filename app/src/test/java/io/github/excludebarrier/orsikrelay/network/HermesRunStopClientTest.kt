package io.github.excludebarrier.orsikrelay.network

import io.github.excludebarrier.orsikrelay.domain.profile.SecretValue
import io.github.excludebarrier.orsikrelay.domain.run.RunStopError
import io.github.excludebarrier.orsikrelay.domain.run.RunStopResult
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
 * Mission 007A: transport contract of the single authenticated
 * `POST /v1/runs/{run_id}/stop` write. The expected wire format was
 * derived read-only from the installed server code
 * (`gateway/platforms/api_server_runs.py`, `_handle_stop_run` /
 * `_load_owned_run`): the handler never reads the request body, the
 * accepted answer is `{"run_id": <id>, "status": "stopping"}`, an
 * already-settled run returns its pollable status object with one of
 * the server's terminal statuses
 * (`completed`, `failed`, `cancelled`, `interrupted`), and ownership /
 * auth failures answer 404 / 401-403 / 409.
 */
class HermesRunStopClientTest {

    private lateinit var server: MockWebServer
    private lateinit var certificate: HeldCertificate
    private lateinit var trustedClient: OkHttpClient
    private lateinit var client: HermesRunStopClient

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
        client = HermesRunStopClient(trustedClient)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun acceptedStopSendsExactPostPathEmptyBodyAndBearerHeaderOnly() {
        server.enqueue(jsonResponse(stoppingBody(RUN_ID)))

        val result = client.stopRun(baseUrl(), SecretValue(FIXTURE_KEY), RUN_ID)

        assertEquals(RunStopResult.Accepted(RUN_ID), result)
        val request = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) {
            "expected one recorded request"
        }
        assertEquals("POST", request.method)
        // Existing base-URL path prefix must be preserved and the run
        // ID must be exactly one encoded path segment.
        assertEquals(
            "/p/android/v1/runs/$RUN_ID/stop",
            request.requestUrl!!.encodedPath
        )
        assertNull(request.requestUrl!!.query)
        assertEquals("Bearer $FIXTURE_KEY", request.getHeader("Authorization"))
        // The server contract has no request body: the handler never
        // reads one, so the exact empty JSON object goes on the wire
        // and no JSON content type is declared.
        assertEquals(EMPTY_BODY, request.body.readUtf8())
        assertNull(request.getHeader("Content-Type"))
        assertFalse(request.requestUrl!!.toString().contains(FIXTURE_KEY))
    }

    @Test
    fun runIdIsAddedAsExactlyOneEncodedSegment() {
        server.enqueue(jsonResponse(stoppingBody(ENCODED_RUN_ID)))

        val result = client.stopRun(baseUrl(), SecretValue(FIXTURE_KEY), ENCODED_RUN_ID)

        assertEquals(RunStopResult.Accepted(ENCODED_RUN_ID), result)
        val request = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) {
            "expected one recorded request"
        }
        // The base-URL path prefix is preserved (as in the accepted-stop
        // test) and the run ID is percent-encoded as exactly one segment:
        // '/' -> %2F, space -> %20; no slash may remain inside the
        // segment.
        assertEquals(
            "/p/android/v1/runs/synthetic%2Frun%20stop/stop",
            request.requestUrl!!.encodedPath
        )
    }

    @Test
    fun stoppingAnswerWithExactRunIdIsAcceptedStop() {
        server.enqueue(jsonResponse(stoppingBody(RUN_ID)))

        assertEquals(RunStopResult.Accepted(RUN_ID), stop())
    }

    @Test
    fun everyServerTerminalStatusIsAlreadyTerminalNotFreshStop() {
        // TERMINAL_STATUSES in api_server_run_idempotency.py:
        // frozenset({"completed", "failed", "cancelled", "interrupted"}).
        listOf("completed", "failed", "cancelled", "interrupted").forEach { status ->
            server.enqueue(jsonResponse(terminalBody(RUN_ID, status)))
            assertEquals(RunStopResult.AlreadyTerminal(RUN_ID), stop())
        }
        assertEquals(4, server.requestCount)
    }

    @Test
    fun terminalStatusObjectWithExtraFieldsStillMapsToAlreadyTerminal() {
        // The server returns its full pollable status object on a
        // settled run; only run_id and status are part of the closed
        // client contract, everything else is ignored.
        val body =
            "{\"object\":\"hermes.run\",\"run_id\":\"$RUN_ID\",\"status\":\"completed\"," +
                "\"created_at\":1000.0,\"updated_at\":1001.0," +
                "\"session_id\":\"synthetic-session\",\"message\":\"dynamic server detail\"}"
        server.enqueue(jsonResponse(body))

        assertEquals(RunStopResult.AlreadyTerminal(RUN_ID), stop())
    }

    @Test
    fun runIdMismatchIsIncompatibleApi() {
        server.enqueue(jsonResponse(stoppingBody("other_run_id")))

        assertEquals(
            RunStopResult.Failure(RunStopError.IncompatibleApi),
            stop()
        )
    }

    @Test
    fun nonTerminalStatusIsIncompatibleApi() {
        // `running` is a server status that exists but is not a stop
        // outcome of this endpoint.
        server.enqueue(jsonResponse(terminalBody(RUN_ID, "running")))

        assertEquals(
            RunStopResult.Failure(RunStopError.IncompatibleApi),
            stop()
        )
    }

    @Test
    fun missingOrWrongTypedFieldsAreIncompatibleApi() {
        listOf(
            "{}",
            "{\"run_id\":\"$RUN_ID\"}",
            "{\"status\":\"stopping\"}",
            "{\"run_id\":42,\"status\":\"stopping\"}",
            "{\"run_id\":\"$RUN_ID\",\"status\":200}",
            "[\"run_id\",\"stopping\"]",
            "\"stopping\"",
            "null"
        ).forEach { body ->
            server.enqueue(jsonResponse(body))
            assertEquals(
                "body $body must be rejected",
                RunStopResult.Failure(RunStopError.IncompatibleApi),
                stop()
            )
        }
    }

    @Test
    fun invalidJsonAndOversizedBodyAreIncompatibleApi() {
        listOf("not json", "", "}{").forEach { body ->
            server.enqueue(jsonResponse(body))
            assertEquals(
                "body $body must be rejected",
                RunStopResult.Failure(RunStopError.IncompatibleApi),
                stop()
            )
        }
        // Well above the client's closed response size limit.
        val oversized = JSONObject().put("run_id", RUN_ID)
            .put("status", "stopping")
            .put("padding", "x".repeat(600 * 1024))
            .toString()
        server.enqueue(jsonResponse(oversized))
        assertEquals(
            RunStopResult.Failure(RunStopError.IncompatibleApi),
            stop()
        )
    }

    @Test
    fun wrongContentTypeIsIncompatibleApiButJsonParametersAreAllowed() {
        listOf("text/html", "application/xml", "text/event-stream", null).forEach { contentType ->
            server.enqueue(
                MockResponse()
                    .apply { if (contentType != null) addHeader("Content-Type", contentType) }
                    .setBody(stoppingBody(RUN_ID))
            )
            assertEquals(
                "content type $contentType must be rejected",
                RunStopResult.Failure(RunStopError.IncompatibleApi),
                stop()
            )
        }
        // A JSON media type with parameters is still JSON.
        server.enqueue(
            MockResponse()
                .addHeader("Content-Type", "application/json; charset=utf-8")
                .setBody(stoppingBody(RUN_ID))
        )
        assertEquals(RunStopResult.Accepted(RUN_ID), stop())
    }

    @Test
    fun httpStatusesMapToClosedErrorsWithoutRetry() {
        listOf(
            401 to RunStopError.InvalidCredentials,
            403 to RunStopError.InvalidCredentials,
            404 to RunStopError.NotFound,
            409 to RunStopError.Conflict,
            429 to RunStopError.RateLimited,
            500 to RunStopError.Http,
            503 to RunStopError.Http
        ).forEach { (code, reason) ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(code)
                    .setBody(
                        "{\"error\":{\"message\":\"dynamic server detail $code\"}}"
                    )
            )
            assertEquals(
                RunStopResult.Failure(reason),
                stop()
            )
        }
        // One request per failure: no automatic retry of the write.
        assertEquals(7, server.requestCount)
    }

    @Test
    fun serverErrorTextNeverReachesTheResult() {
        server.enqueue(
            MockResponse()
                .setResponseCode(404)
                .setBody(
                    "{\"error\":{\"message\":\"Run not found: $RUN_ID, key $FIXTURE_KEY\"}}"
                )
        )

        val result = stop()

        assertEquals(RunStopResult.Failure(RunStopError.NotFound), result)
        assertFalse(result.toString().contains("Run not found"))
        assertFalse(result.toString().contains(FIXTURE_KEY))
        assertFalse(result.toString().contains(RUN_ID))
    }

    @Test
    fun redirectIsNotFollowedAndMapsToHttp() {
        server.enqueue(
            MockResponse().setResponseCode(302)
                .addHeader("Location", server.url("/redirect-target"))
        )
        server.enqueue(jsonResponse(stoppingBody(RUN_ID)))

        val result = stop()

        assertEquals(RunStopResult.Failure(RunStopError.Http), result)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun tlsFailureMapsToClosedTlsErrorWithoutLeakingKeyOrUrl() {
        server.enqueue(jsonResponse(stoppingBody(RUN_ID)))
        val untrusted = HermesRunStopClient(OkHttpClient.Builder().build())

        val result = untrusted.stopRun(baseUrl(), SecretValue(FIXTURE_KEY), RUN_ID)

        assertEquals(RunStopResult.Failure(RunStopError.Tls), result)
        assertFalse(result.toString().contains(FIXTURE_KEY))
    }

    @Test
    fun timeoutMapsToClosedTimeoutError() {
        val shortClient = trustedClient.newBuilder()
            .readTimeout(100, TimeUnit.MILLISECONDS)
            .callTimeout(300, TimeUnit.MILLISECONDS)
            .build()
        server.enqueue(
            jsonResponse(stoppingBody(RUN_ID))
                .setBodyDelay(2000, TimeUnit.MILLISECONDS)
        )

        val result = HermesRunStopClient(shortClient)
            .stopRun(baseUrl(), SecretValue(FIXTURE_KEY), RUN_ID)

        assertEquals(RunStopResult.Failure(RunStopError.Timeout), result)
    }

    @Test
    fun unreachableServerMapsToClosedNetworkError() {
        val stopped = MockWebServer().apply { start() }
        val unavailable = stopped.url("/").toString().replace("http://", "https://")
        stopped.shutdown()

        val result = client.stopRun(unavailable, SecretValue(FIXTURE_KEY), RUN_ID)

        assertTrue(
            "expected a closed transport failure, got $result",
            result == RunStopResult.Failure(RunStopError.Network) ||
                result == RunStopResult.Failure(RunStopError.Tls)
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
            baseUrl() to "control-char-run-id",
            baseUrl() to "overlong-run-id",
            baseUrl() to "blank-run-id"
        ).forEach { (url, label) ->
            val key = if (label == "blank-key") "" else FIXTURE_KEY
            val runId = when (label) {
                "control-char-run-id" -> "run\u0001synthetic"
                "overlong-run-id" -> "r".repeat(257)
                "blank-run-id" -> "   "
                else -> RUN_ID
            }
            val result = client.stopRun(url, SecretValue(key), runId)
            assertEquals(
                "$label must fail closed locally",
                RunStopResult.Failure(RunStopError.InvalidInput),
                result
            )
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun keyNeverAppearsInUrlBodyOrResult() {
        server.enqueue(jsonResponse(stoppingBody(RUN_ID)))

        val result = client.stopRun(baseUrl(), SecretValue(FIXTURE_KEY), RUN_ID)

        val request = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) {
            "expected one recorded request"
        }
        assertFalse(request.requestUrl!!.toString().contains(FIXTURE_KEY))
        assertFalse(request.body.readUtf8().contains(FIXTURE_KEY))
        assertFalse(result.toString().contains(FIXTURE_KEY))
    }

    private fun stop(): RunStopResult =
        client.stopRun(baseUrl(), SecretValue(FIXTURE_KEY), RUN_ID)

    private fun baseUrl(): String =
        server.url("/p/android").toString().trimEnd('/')

    private fun jsonResponse(body: String): MockResponse = MockResponse()
        .addHeader("Content-Type", "application/json")
        .setBody(body)

    private fun stoppingBody(runId: String): String =
        "{\"run_id\":\"$runId\",\"status\":\"stopping\"}"

    private fun terminalBody(runId: String, status: String): String =
        "{\"run_id\":\"$runId\",\"status\":\"$status\"}"

    private companion object {
        const val FIXTURE_KEY = "fixture-credential"
        const val RUN_ID = "run_synthetic"
        const val ENCODED_RUN_ID = "synthetic/run stop"
        const val EMPTY_BODY = "{}"
    }
}
