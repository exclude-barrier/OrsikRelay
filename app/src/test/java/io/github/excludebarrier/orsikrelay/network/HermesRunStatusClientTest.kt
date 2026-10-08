package io.github.excludebarrier.orsikrelay.network

import io.github.excludebarrier.orsikrelay.domain.profile.SecretValue
import io.github.excludebarrier.orsikrelay.domain.run.RunServerStatus
import io.github.excludebarrier.orsikrelay.domain.run.RunStatusError
import io.github.excludebarrier.orsikrelay.domain.run.RunStatusResult
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
 * P7: transport contract of the single authenticated
 * `GET /v1/runs/{run_id}` read. The expected wire format was derived
 * read-only from the installed server code
 * (`gateway/platforms/api_server_runs.py`, `_handle_get_run` /
 * `_load_owned_run`): 200 with the pollable run status object, the closed
 * status vocabulary (`queued`, `running`, `waiting_for_approval`,
 * `stopping`, `completed`, `failed`, `cancelled`, `interrupted`), and
 * 404 / 401-403 for a foreign or unauthorized run.
 */
class HermesRunStatusClientTest {

    private lateinit var server: MockWebServer
    private lateinit var certificate: HeldCertificate
    private lateinit var trustedClient: OkHttpClient
    private lateinit var client: HermesRunStatusClient

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
            .protocols(listOf(Protocol.HTTP_1_1))
            .build()
        client = HermesRunStatusClient(trustedClient)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun statusReadSendsExactGetPathNoBodyAndBearerHeaderOnly() {
        server.enqueue(jsonResponse(statusBody(RUN_ID, "running")))

        val result = client.getRunStatus(baseUrl(), SecretValue(FIXTURE_KEY), RUN_ID)

        assertEquals(RunStatusResult.Known(RunServerStatus.RUNNING), result)
        val request = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) {
            "expected one recorded request"
        }
        assertEquals("GET", request.method)
        // Existing base-URL path prefix must be preserved and the run ID
        // must be exactly one encoded path segment.
        assertEquals("/p/android/v1/runs/$RUN_ID", request.requestUrl!!.encodedPath)
        assertNull(request.requestUrl!!.query)
        assertEquals("Bearer $FIXTURE_KEY", request.getHeader("Authorization"))
        assertEquals(0L, request.bodySize)
        assertFalse(request.requestUrl!!.toString().contains(FIXTURE_KEY))
    }

    @Test
    fun runIdIsAddedAsExactlyOneEncodedSegment() {
        server.enqueue(jsonResponse(statusBody(ENCODED_RUN_ID, "running")))

        val result = client.getRunStatus(baseUrl(), SecretValue(FIXTURE_KEY), ENCODED_RUN_ID)

        assertEquals(RunStatusResult.Known(RunServerStatus.RUNNING), result)
        val request = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) {
            "expected one recorded request"
        }
        assertEquals(
            "/p/android/v1/runs/synthetic%2Frun%20stop",
            request.requestUrl!!.encodedPath
        )
    }

    @Test
    fun everyServerStatusMapsToTheClosedVocabulary() {
        // The closed vocabulary of _set_run_status / TERMINAL_STATUSES.
        listOf(
            "queued" to RunServerStatus.QUEUED,
            "running" to RunServerStatus.RUNNING,
            "waiting_for_approval" to RunServerStatus.WAITING_FOR_APPROVAL,
            "stopping" to RunServerStatus.STOPPING,
            "completed" to RunServerStatus.COMPLETED,
            "failed" to RunServerStatus.FAILED,
            "cancelled" to RunServerStatus.CANCELLED,
            "interrupted" to RunServerStatus.INTERRUPTED
        ).forEach { (wire, status) ->
            server.enqueue(jsonResponse(statusBody(RUN_ID, wire)))
            assertEquals(RunStatusResult.Known(status), read())
        }
        assertEquals(8, server.requestCount)
    }

    @Test
    fun onlyTheFourTerminalStatusesAreTerminal() {
        assertTrue(RunServerStatus.COMPLETED.isTerminal)
        assertTrue(RunServerStatus.FAILED.isTerminal)
        assertTrue(RunServerStatus.CANCELLED.isTerminal)
        assertTrue(RunServerStatus.INTERRUPTED.isTerminal)
        assertFalse(RunServerStatus.QUEUED.isTerminal)
        assertFalse(RunServerStatus.RUNNING.isTerminal)
        assertFalse(RunServerStatus.WAITING_FOR_APPROVAL.isTerminal)
        assertFalse(RunServerStatus.STOPPING.isTerminal)
    }

    @Test
    fun fullStatusObjectWithExtraFieldsIgnoresEverythingButRunIdAndStatus() {
        // The server returns its full pollable status object; output, error,
        // usage, session_id and message are deliberately not read.
        val body =
            "{\"object\":\"hermes.run\",\"run_id\":\"$RUN_ID\",\"status\":\"completed\"," +
                "\"created_at\":1000.0,\"updated_at\":1001.0," +
                "\"session_id\":\"synthetic-session\",\"output\":\"assistant answer\"," +
                "\"error\":\"dynamic server detail\",\"usage\":{\"total_tokens\":42}," +
                "\"pending_steer\":\"draft\"}"
        server.enqueue(jsonResponse(body))

        assertEquals(RunStatusResult.Known(RunServerStatus.COMPLETED), read())
    }

    @Test
    fun runIdMismatchIsIncompatibleApi() {
        server.enqueue(jsonResponse(statusBody("other_run_id", "running")))

        assertEquals(
            RunStatusResult.Failure(RunStatusError.IncompatibleApi),
            read()
        )
    }

    @Test
    fun unknownStatusIsIncompatibleApiNotAGuessedState() {
        server.enqueue(jsonResponse(statusBody(RUN_ID, "half_open")))

        assertEquals(
            RunStatusResult.Failure(RunStatusError.IncompatibleApi),
            read()
        )
    }

    @Test
    fun missingOrWrongTypedFieldsAreIncompatibleApi() {
        listOf(
            "{}",
            "{\"run_id\":\"$RUN_ID\"}",
            "{\"status\":\"running\"}",
            "{\"run_id\":42,\"status\":\"running\"}",
            "{\"run_id\":\"$RUN_ID\",\"status\":200}",
            "[\"run_id\",\"running\"]",
            "\"running\"",
            "null"
        ).forEach { body ->
            server.enqueue(jsonResponse(body))
            assertEquals(
                "body $body must be rejected",
                RunStatusResult.Failure(RunStatusError.IncompatibleApi),
                read()
            )
        }
    }

    @Test
    fun invalidJsonAndOversizedBodyAreIncompatibleApi() {
        listOf("not json", "", "}{").forEach { body ->
            server.enqueue(jsonResponse(body))
            assertEquals(
                "body $body must be rejected",
                RunStatusResult.Failure(RunStatusError.IncompatibleApi),
                read()
            )
        }
        val oversized = JSONObject().put("run_id", RUN_ID)
            .put("status", "running")
            .put("padding", "x".repeat(600 * 1024))
            .toString()
        server.enqueue(jsonResponse(oversized))
        assertEquals(
            RunStatusResult.Failure(RunStatusError.IncompatibleApi),
            read()
        )
    }

    @Test
    fun wrongContentTypeIsIncompatibleApiButJsonParametersAreAllowed() {
        listOf("text/html", "application/xml", "text/event-stream", null).forEach { contentType ->
            server.enqueue(
                MockResponse()
                    .apply { if (contentType != null) addHeader("Content-Type", contentType) }
                    .setBody(statusBody(RUN_ID, "running"))
            )
            assertEquals(
                "content type $contentType must be rejected",
                RunStatusResult.Failure(RunStatusError.IncompatibleApi),
                read()
            )
        }
        server.enqueue(
            MockResponse()
                .addHeader("Content-Type", "application/json; charset=utf-8")
                .setBody(statusBody(RUN_ID, "running"))
        )
        assertEquals(RunStatusResult.Known(RunServerStatus.RUNNING), read())
    }

    @Test
    fun httpStatusesMapToClosedErrorsWithoutRetry() {
        listOf(
            401 to RunStatusError.InvalidCredentials,
            403 to RunStatusError.InvalidCredentials,
            404 to RunStatusError.NotFound,
            409 to RunStatusError.Conflict,
            429 to RunStatusError.RateLimited,
            500 to RunStatusError.Http,
            503 to RunStatusError.Http
        ).forEach { (code, reason) ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(code)
                    .setBody("{\"error\":{\"message\":\"dynamic server detail $code\"}}")
            )
            assertEquals(RunStatusResult.Failure(reason), read())
        }
        // One request per failure: no automatic retry of the read.
        assertEquals(7, server.requestCount)
    }

    @Test
    fun serverErrorTextNeverReachesTheResult() {
        server.enqueue(
            MockResponse()
                .setResponseCode(404)
                .setBody("{\"error\":{\"message\":\"Run not found: $RUN_ID, key $FIXTURE_KEY\"}}")
        )

        val result = read()

        assertEquals(RunStatusResult.Failure(RunStatusError.NotFound), result)
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
        server.enqueue(jsonResponse(statusBody(RUN_ID, "running")))

        assertEquals(RunStatusResult.Failure(RunStatusError.Http), read())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun tlsFailureMapsToClosedTlsErrorWithoutLeakingKeyOrUrl() {
        server.enqueue(jsonResponse(statusBody(RUN_ID, "running")))
        val untrusted = HermesRunStatusClient(OkHttpClient.Builder().build())

        val result = untrusted.getRunStatus(baseUrl(), SecretValue(FIXTURE_KEY), RUN_ID)

        assertEquals(RunStatusResult.Failure(RunStatusError.Tls), result)
        assertFalse(result.toString().contains(FIXTURE_KEY))
    }

    @Test
    fun timeoutMapsToClosedTimeoutError() {
        val shortClient = trustedClient.newBuilder()
            .readTimeout(100, TimeUnit.MILLISECONDS)
            .callTimeout(300, TimeUnit.MILLISECONDS)
            .build()
        server.enqueue(
            jsonResponse(statusBody(RUN_ID, "running"))
                .setBodyDelay(2000, TimeUnit.MILLISECONDS)
        )

        val result = HermesRunStatusClient(shortClient)
            .getRunStatus(baseUrl(), SecretValue(FIXTURE_KEY), RUN_ID)

        assertEquals(RunStatusResult.Failure(RunStatusError.Timeout), result)
    }

    @Test
    fun unreachableServerMapsToClosedNetworkError() {
        val stopped = MockWebServer().apply { start() }
        val unavailable = stopped.url("/").toString().replace("http://", "https://")
        stopped.shutdown()

        val result = client.getRunStatus(unavailable, SecretValue(FIXTURE_KEY), RUN_ID)

        assertTrue(
            "expected a closed transport failure, got $result",
            result == RunStatusResult.Failure(RunStatusError.Network) ||
                result == RunStatusResult.Failure(RunStatusError.Tls)
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
            val result = client.getRunStatus(url, SecretValue(key), runId)
            assertEquals(
                "$label must fail closed locally",
                RunStatusResult.Failure(RunStatusError.InvalidInput),
                result
            )
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun keyNeverAppearsInUrlOrResult() {
        server.enqueue(jsonResponse(statusBody(RUN_ID, "running")))

        val result = client.getRunStatus(baseUrl(), SecretValue(FIXTURE_KEY), RUN_ID)

        val request = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) {
            "expected one recorded request"
        }
        assertFalse(request.requestUrl!!.toString().contains(FIXTURE_KEY))
        assertFalse(result.toString().contains(FIXTURE_KEY))
    }

    private fun read(): RunStatusResult =
        client.getRunStatus(baseUrl(), SecretValue(FIXTURE_KEY), RUN_ID)

    private fun baseUrl(): String =
        server.url("/p/android").toString().trimEnd('/')

    private fun jsonResponse(body: String): MockResponse = MockResponse()
        .addHeader("Content-Type", "application/json")
        .setBody(body)

    private fun statusBody(runId: String, status: String): String =
        "{\"object\":\"hermes.run\",\"run_id\":\"$runId\",\"status\":\"$status\"}"

    private companion object {
        const val FIXTURE_KEY = "fixture-credential"
        const val RUN_ID = "run_synthetic"
        const val ENCODED_RUN_ID = "synthetic/run stop"
    }
}
