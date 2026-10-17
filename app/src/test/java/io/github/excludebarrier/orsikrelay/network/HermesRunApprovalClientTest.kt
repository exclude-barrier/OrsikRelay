package io.github.excludebarrier.orsikrelay.network

import io.github.excludebarrier.orsikrelay.domain.profile.SecretValue
import io.github.excludebarrier.orsikrelay.domain.run.RunApprovalChoice
import io.github.excludebarrier.orsikrelay.domain.run.RunApprovalError
import io.github.excludebarrier.orsikrelay.domain.run.RunApprovalResult
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
 * Transport contract of the single authenticated
 * `POST /v1/runs/{run_id}/approval` decision write. The wire format was
 * derived read-only from the installed server code
 * (`gateway/platforms/api_server_runs.py`, `_handle_run_approval`): the
 * handler reads the JSON body `{"choice": <once|deny>, "request_id": <id>}`
 * with `Content-Type: application/json` and a Bearer key, and answers 200
 * with `{"object":"hermes.run.approval_response","run_id":<id>,
 * "choice":<sent choice>,"resolved":<n>=1>}`; an already-resolved or
 * expired approval answers 409, ownership/auth 404 / 401-403, rate limit
 * 429. Only `once` and `deny` are modelled in the client.
 */
class HermesRunApprovalClientTest {

    private lateinit var server: MockWebServer
    private lateinit var certificate: HeldCertificate
    private lateinit var trustedClient: OkHttpClient
    private lateinit var client: HermesRunApprovalClient

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
        // stop/streaming tests do (MockWebServer h2 framing corruption).
        trustedClient = OkHttpClient.Builder()
            .sslSocketFactory(
                clientCertificates.sslSocketFactory(),
                clientCertificates.trustManager
            )
            .protocols(listOf(Protocol.HTTP_1_1))
            .build()
        client = HermesRunApprovalClient(trustedClient)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun acceptedOnceSendsExactPostPathBodyContentTypeAndBearerHeaderOnly() {
        server.enqueue(jsonResponse(approvalBody(RUN_ID, "once")))

        val result = client.respondApproval(baseUrl(), SecretValue(FIXTURE_KEY), RUN_ID, REQUEST_ID, RunApprovalChoice.ONCE)

        assertEquals(RunApprovalResult.Resolved(RUN_ID, RunApprovalChoice.ONCE), result)
        val request = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) {
            "expected one recorded request"
        }
        assertEquals("POST", request.method)
        // Base-URL path prefix is preserved and the run ID is exactly one
        // encoded path segment.
        assertEquals(
            "/p/android/v1/runs/$RUN_ID/approval",
            request.requestUrl!!.encodedPath
        )
        assertNull(request.requestUrl!!.query)
        assertEquals("Bearer $FIXTURE_KEY", request.getHeader("Authorization"))
        // The body carries exactly the choice and the request id, and the
        // JSON media type is declared.
        val sentBody = JSONObject(request.body.readUtf8())
        assertEquals(2, sentBody.length())
        assertEquals("once", sentBody.getString("choice"))
        assertEquals(REQUEST_ID, sentBody.getString("request_id"))
        assertEquals("application/json", request.getHeader("Content-Type")?.substringBefore(';')?.trim())
        assertFalse(request.requestUrl!!.toString().contains(FIXTURE_KEY))
    }

    @Test
    fun acceptedDenySendsDenyChoiceAndResolves() {
        server.enqueue(jsonResponse(approvalBody(RUN_ID, "deny")))

        val result = client.respondApproval(baseUrl(), SecretValue(FIXTURE_KEY), RUN_ID, REQUEST_ID, RunApprovalChoice.DENY)

        assertEquals(RunApprovalResult.Resolved(RUN_ID, RunApprovalChoice.DENY), result)
        val request = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) {
            "expected one recorded request"
        }
        val sentBody = JSONObject(request.body.readUtf8())
        assertEquals("deny", sentBody.getString("choice"))
        assertEquals(REQUEST_ID, sentBody.getString("request_id"))
    }

    @Test
    fun runIdIsAddedAsExactlyOneEncodedSegment() {
        server.enqueue(jsonResponse(approvalBody(ENCODED_RUN_ID, "once")))

        val result = client.respondApproval(baseUrl(), SecretValue(FIXTURE_KEY), ENCODED_RUN_ID, REQUEST_ID, RunApprovalChoice.ONCE)

        assertEquals(RunApprovalResult.Resolved(ENCODED_RUN_ID, RunApprovalChoice.ONCE), result)
        val request = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) {
            "expected one recorded request"
        }
        // '/' -> %2F, space -> %20; no slash may remain inside the segment.
        assertEquals(
            "/p/android/v1/runs/synthetic%2Frun%20approval/approval",
            request.requestUrl!!.encodedPath
        )
    }

    @Test
    fun wrongObjectRunIdChoiceOrResolvedBelowOneAreIncompatibleApi() {
        listOf(
            // wrong / missing object discriminator
            "{\"object\":\"hermes.run\",\"run_id\":\"$RUN_ID\",\"choice\":\"once\",\"resolved\":1}",
            "{\"run_id\":\"$RUN_ID\",\"choice\":\"once\",\"resolved\":1}",
            // wrong run id
            "{\"object\":\"hermes.run.approval_response\",\"run_id\":\"other_run\",\"choice\":\"once\",\"resolved\":1}",
            // echo of a different choice than the one sent
            "{\"object\":\"hermes.run.approval_response\",\"run_id\":\"$RUN_ID\",\"choice\":\"deny\",\"resolved\":1}",
            // resolved = 0 means the approval was not resolved
            "{\"object\":\"hermes.run.approval_response\",\"run_id\":\"$RUN_ID\",\"choice\":\"once\",\"resolved\":0}",
            // resolved as a string, not an integer
            "{\"object\":\"hermes.run.approval_response\",\"run_id\":\"$RUN_ID\",\"choice\":\"once\",\"resolved\":\"1\"}"
        ).forEach { body ->
            server.enqueue(jsonResponse(body))
            assertEquals(
                "body $body must be rejected",
                RunApprovalResult.Failure(RunApprovalError.IncompatibleApi),
                respond(once = true)
            )
        }
    }

    @Test
    fun wrongTypedFieldsAndMissingFieldsAreIncompatibleApi() {
        listOf(
            "{}",
            "{\"object\":\"hermes.run.approval_response\"}",
            "{\"object\":\"hermes.run.approval_response\",\"run_id\":\"$RUN_ID\",\"choice\":\"once\"}",
            "{\"object\":\"hermes.run.approval_response\",\"run_id\":42,\"choice\":\"once\",\"resolved\":1}",
            "{\"object\":\"hermes.run.approval_response\",\"run_id\":\"$RUN_ID\",\"choice\":1,\"resolved\":1}",
            "[\"hermes.run.approval_response\",\"$RUN_ID\",\"once\"]",
            "\"hermes.run.approval_response\"",
            "null"
        ).forEach { body ->
            server.enqueue(jsonResponse(body))
            assertEquals(
                "body $body must be rejected",
                RunApprovalResult.Failure(RunApprovalError.IncompatibleApi),
                respond(once = true)
            )
        }
    }

    @Test
    fun nonJsonAndOversizedBodyAreIncompatibleApi() {
        listOf("not json", "", "}{").forEach { body ->
            server.enqueue(jsonResponse(body))
            assertEquals(
                "body $body must be rejected",
                RunApprovalResult.Failure(RunApprovalError.IncompatibleApi),
                respond(once = true)
            )
        }
        val oversized = JSONObject()
            .put("object", "hermes.run.approval_response")
            .put("run_id", RUN_ID)
            .put("choice", "once")
            .put("resolved", 1)
            .put("padding", "x".repeat(600 * 1024))
            .toString()
        server.enqueue(jsonResponse(oversized))
        assertEquals(
            RunApprovalResult.Failure(RunApprovalError.IncompatibleApi),
            respond(once = true)
        )
    }

    @Test
    fun wrongContentTypeIsIncompatibleApiButJsonParametersAreAllowed() {
        listOf("text/html", "application/xml", "text/event-stream", null).forEach { contentType ->
            server.enqueue(
                MockResponse()
                    .apply { if (contentType != null) addHeader("Content-Type", contentType) }
                    .setBody(approvalBody(RUN_ID, "once"))
            )
            assertEquals(
                "content type $contentType must be rejected",
                RunApprovalResult.Failure(RunApprovalError.IncompatibleApi),
                respond(once = true)
            )
        }
        server.enqueue(
            MockResponse()
                .addHeader("Content-Type", "application/json; charset=utf-8")
                .setBody(approvalBody(RUN_ID, "once"))
        )
        assertEquals(RunApprovalResult.Resolved(RUN_ID, RunApprovalChoice.ONCE), respond(once = true))
    }

    @Test
    fun httpStatusesMapToClosedErrorsWithoutRetry() {
        listOf(
            409 to RunApprovalResult.NoPending(RUN_ID),
            401 to RunApprovalResult.Failure(RunApprovalError.InvalidCredentials),
            403 to RunApprovalResult.Failure(RunApprovalError.InvalidCredentials),
            404 to RunApprovalResult.Failure(RunApprovalError.NotFound),
            429 to RunApprovalResult.Failure(RunApprovalError.RateLimited),
            500 to RunApprovalResult.Failure(RunApprovalError.Http),
            503 to RunApprovalResult.Failure(RunApprovalError.Http)
        ).forEach { (code, expected) ->
            val body = if (code == 409) {
                // Only the documented conflict code maps to NoPending.
                "{\"error\":{\"code\":\"approval_not_pending\"}}"
            } else {
                "{\"error\":{\"message\":\"dynamic server detail $code $RUN_ID\"}}"
            }
            server.enqueue(
                MockResponse()
                    .setResponseCode(code)
                    .setBody(body)
            )
            assertEquals("code $code", expected, respond(once = true))
        }
        // One request per failure: the decision write is never retried.
        assertEquals(7, server.requestCount)
    }

    @Test
    fun conflictWithUnknownCodeIsClosedHttpFailure() {
        // A 409 whose error.code is not the documented conflict code must NOT
        // be reported as NoPending.
        server.enqueue(
            MockResponse()
                .setResponseCode(409)
                .setBody("{\"error\":{\"code\":\"something_else\"}}")
        )
        assertEquals(RunApprovalResult.Failure(RunApprovalError.Http), respond(once = true))
    }

    @Test
    fun conflictIsClosedNoPendingWithoutDetails() {
        server.enqueue(
            MockResponse()
                .setResponseCode(409)
                .setBody("{\"error\":{\"code\":\"approval_not_pending\"}}")
        )

        val result = respond(once = true)

        // NoPending is the closed terminal outcome; it carries the run id
        // (as the domain model, like RunStopResult.AlreadyTerminal) but never
        // the request id, key or server error text.
        assertEquals(RunApprovalResult.NoPending(RUN_ID), result)
        assertFalse(result.toString().contains(REQUEST_ID))
        assertFalse(result.toString().contains(FIXTURE_KEY))
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

        val result = respond(once = true)

        assertEquals(RunApprovalResult.Failure(RunApprovalError.NotFound), result)
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
        server.enqueue(jsonResponse(approvalBody(RUN_ID, "once")))

        val result = respond(once = true)

        assertEquals(RunApprovalResult.Failure(RunApprovalError.Http), result)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun tlsFailureMapsToClosedTlsErrorWithoutLeakingKeyOrUrl() {
        server.enqueue(jsonResponse(approvalBody(RUN_ID, "once")))
        val untrusted = HermesRunApprovalClient(OkHttpClient.Builder().build())

        val result = untrusted.respondApproval(baseUrl(), SecretValue(FIXTURE_KEY), RUN_ID, REQUEST_ID, RunApprovalChoice.ONCE)

        assertEquals(RunApprovalResult.Failure(RunApprovalError.Tls), result)
        assertFalse(result.toString().contains(FIXTURE_KEY))
    }

    @Test
    fun unreachableServerMapsToClosedTransportError() {
        val stopped = MockWebServer().apply { start() }
        val unavailable = stopped.url("/").toString().replace("http://", "https://")
        stopped.shutdown()

        val result = client.respondApproval(unavailable, SecretValue(FIXTURE_KEY), RUN_ID, REQUEST_ID, RunApprovalChoice.ONCE)

        assertTrue(
            "expected a closed transport failure, got $result",
            result is RunApprovalResult.Failure
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
            baseUrl() to "overlong-key",
            baseUrl() to "control-char-key",
            baseUrl() to "blank-run-id",
            baseUrl() to "overlong-run-id",
            baseUrl() to "control-char-run-id",
            baseUrl() to "blank-request-id",
            baseUrl() to "overlong-request-id",
            baseUrl() to "control-char-request-id"
        ).forEach { (url, label) ->
            val key = when (label) {
                "blank-key" -> ""
                "whitespace-key" -> "has whitespace"
                "overlong-key" -> "k".repeat(4097)
                "control-char-key" -> "key\u0001"
                else -> FIXTURE_KEY
            }
            val runId = when (label) {
                "blank-run-id" -> "   "
                "overlong-run-id" -> "r".repeat(257)
                "control-char-run-id" -> "run\u0001"
                else -> RUN_ID
            }
            val requestId = when (label) {
                "blank-request-id" -> "   "
                "overlong-request-id" -> "r".repeat(257)
                "control-char-request-id" -> "req\u0001"
                else -> REQUEST_ID
            }
            val result = client.respondApproval(url, SecretValue(key), runId, requestId, RunApprovalChoice.ONCE)
            assertEquals(
                "$label must fail closed locally",
                RunApprovalResult.Failure(RunApprovalError.InvalidInput),
                result
            )
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun keyNeverAppearsInUrlBodyOrResult() {
        server.enqueue(jsonResponse(approvalBody(RUN_ID, "once")))

        val result = client.respondApproval(baseUrl(), SecretValue(FIXTURE_KEY), RUN_ID, REQUEST_ID, RunApprovalChoice.ONCE)

        val request = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) {
            "expected one recorded request"
        }
        assertFalse(request.requestUrl!!.toString().contains(FIXTURE_KEY))
        assertFalse(request.body.readUtf8().contains(FIXTURE_KEY))
        assertFalse(result.toString().contains(FIXTURE_KEY))
    }

    private fun respond(once: Boolean): RunApprovalResult =
        client.respondApproval(
            baseUrl(),
            SecretValue(FIXTURE_KEY),
            RUN_ID,
            REQUEST_ID,
            if (once) RunApprovalChoice.ONCE else RunApprovalChoice.DENY
        )

    private fun baseUrl(): String =
        server.url("/p/android").toString().trimEnd('/')

    private fun jsonResponse(body: String): MockResponse = MockResponse()
        .addHeader("Content-Type", "application/json")
        .setBody(body)

    private fun approvalBody(runId: String, choice: String): String =
        "{\"object\":\"hermes.run.approval_response\",\"run_id\":\"$runId\",\"choice\":\"$choice\",\"resolved\":1}"

    private companion object {
        const val FIXTURE_KEY = "fixture-credential"
        const val RUN_ID = "run_synthetic"
        const val ENCODED_RUN_ID = "synthetic/run approval"
        const val REQUEST_ID = "request_synthetic"
    }
}
