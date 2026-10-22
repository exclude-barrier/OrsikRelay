package io.github.excludebarrier.orsikrelay.network

import io.github.excludebarrier.orsikrelay.domain.message.MessageHistoryError
import io.github.excludebarrier.orsikrelay.domain.message.MessageHistoryResult
import io.github.excludebarrier.orsikrelay.domain.message.MessageRole
import io.github.excludebarrier.orsikrelay.domain.profile.SecretValue
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

/**
 * Contract tests for the authenticated `GET /api/sessions/{id}/messages`
 * read. Fully synthetic; no real server, key or content.
 */
class HermesMessageClientTest {

    private lateinit var server: MockWebServer
    private lateinit var certificate: HeldCertificate
    private lateinit var trustedClient: OkHttpClient
    private lateinit var client: HermesMessageClient

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
        client = HermesMessageClient(trustedClient)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun successfulRequestUsesExactPathQueryAndBearerHeader() {
        server.enqueue(jsonResponse(validPage()))

        val result = fetch("synthetic session id")

        assertTrue(result is MessageHistoryResult.Success)
        assertTrue((result as MessageHistoryResult.Success).page.messages.isEmpty())

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals(
            "/api/sessions/synthetic%20session%20id/messages",
            request.requestUrl!!.encodedPath
        )
        assertEquals("200", request.requestUrl!!.queryParameter("limit"))
        assertEquals("0", request.requestUrl!!.queryParameter("offset"))
        assertEquals("latest", request.requestUrl!!.queryParameter("order"))
        assertEquals("Bearer $FIXTURE_KEY", request.getHeader("Authorization"))
        assertFalse(request.requestUrl!!.toString().contains(FIXTURE_KEY))
        assertFalse(result.toString().contains(FIXTURE_KEY))
    }

    @Test
    fun olderRequestSendsTheOffsetAndParsesPaginationMetadata() {
        val body = base()
            .put(
                "data",
                JSONArray()
                    .put(message(101, "user", "Älter eins"))
                    .put(message(102, "assistant", "Älter zwei"))
            )
            .put("pagination", pagination(200, 200, 2))
        server.enqueue(jsonResponse(body))

        val result = fetch(offset = 200) as MessageHistoryResult.Success
        val page = result.page

        assertEquals("200", server.takeRequest().requestUrl!!.queryParameter("offset"))
        assertEquals(listOf(101L, 102L), page.messages.map { it.id })
        assertEquals(200, page.limit)
        assertEquals(2, page.returned)
        assertFalse(page.hasMoreOnServer)
    }

    @Test
    fun hiddenRowsStayInTheReturnedCountUsedForTheNextOffset() {
        val body = base()
            .put(
                "data",
                JSONArray()
                    .put(message(101, "user", "Sichtbar"))
                    .put(message(102, "assistant", "Verborgen").put("display_kind", "hidden"))
            )
            .put("pagination", pagination(2, 200, 2))
        server.enqueue(jsonResponse(body))

        val page = (fetch(offset = 200) as MessageHistoryResult.Success).page

        assertEquals(listOf(101L), page.messages.map { it.id })
        assertEquals(2, page.returned)
        assertEquals(2, page.limit)
        assertTrue(page.hasMoreOnServer)
    }

    @Test
    fun fullReturnedPageReportsThatAnOlderPageMayExist() {
        val data = JSONArray().apply {
            for (id in 1L..200L) put(message(id, "user", "Nachricht $id"))
        }
        server.enqueue(
            jsonResponse(
                base()
                    .put("data", data)
                    .put("pagination", pagination(200, 0, 200))
            )
        )

        val page = (fetch() as MessageHistoryResult.Success).page

        assertEquals(200, page.returned)
        assertTrue(page.hasMoreOnServer)
    }

    @Test
    fun negativeOffsetIsRejectedWithoutARequest() {
        assertFailure(
            client.fetch(baseUrl(), SecretValue(FIXTURE_KEY), SESSION_ID, -1),
            MessageHistoryError.InvalidInput
        )
        assertEquals(0, server.requestCount)
    }

    @Test
    fun sessionIdIsEncodedAsExactlyOnePathSegment() {
        server.enqueue(jsonResponse(validPage()))

        val result = fetch("a/b?c#d&e")

        assertTrue(result is MessageHistoryResult.Success)
        val request = server.takeRequest()
        assertEquals(
            "/api/sessions/a%2Fb%3Fc%23d&e/messages",
            request.requestUrl!!.encodedPath
        )
    }

    @Test
    fun visibleMessagesPreserveServerOrderAndTypedFields() {
        val body = base()
            .put(
                "data",
                JSONArray()
                    .put(message(1, "user", "Hallo").put("timestamp", 1000))
                    .put(message(2, "assistant", "Hallo zurück"))
                    .put(message(3, "system", "Kontext"))
                    .put(message(4, "tool", "Ergebnis").put("tool_name", "web_search"))
            )
            .put("pagination", pagination(200, 0, 4))
        server.enqueue(jsonResponse(body))

        val result = fetch() as MessageHistoryResult.Success
        val messages = result.page.messages

        assertEquals(listOf(1L, 2L, 3L, 4L), messages.map { it.id })
        assertEquals(
            listOf(
                MessageRole.USER,
                MessageRole.ASSISTANT,
                MessageRole.SYSTEM,
                MessageRole.TOOL
            ),
            messages.map { it.role }
        )
        assertEquals("Hallo", messages[0].content)
        assertEquals(1000L, messages[0].timestampSeconds)
        assertNull(messages[0].toolName)
        assertNull(messages[1].timestampSeconds)
        assertEquals("web_search", messages[3].toolName)
        assertFalse(result.toString().contains(FIXTURE_KEY))
    }

    @Test
    fun hiddenMessagesAreDroppedWhileVisibleOrderStaysIntact() {
        val body = base()
            .put(
                "data",
                JSONArray()
                    .put(message(1, "user", "Sichtbar"))
                    .put(message(2, "assistant", "Verborgen").put("display_kind", "hidden"))
                    .put(message(3, "user", "Sichtbar zwei"))
                    .put(message(4, "system", "Verborgen zwei").put("display_kind", "hidden"))
            )
            .put("pagination", pagination(200, 0, 4))
        server.enqueue(jsonResponse(body))

        val result = fetch() as MessageHistoryResult.Success

        assertEquals(listOf(1L, 3L), result.page.messages.map { it.id })
        assertFalse(result.toString().contains("Verborgen"))
    }

    @Test
    fun duplicateVisibleMessageIdsAreRejectedAsIncompatible() {
        val body = base()
            .put(
                "data",
                JSONArray()
                    .put(message(1, "user", "Erste"))
                    .put(message(1, "assistant", "Zweite"))
            )
            .put("pagination", pagination(200, 0, 2))
        server.enqueue(jsonResponse(body))

        assertFailure(fetch(), MessageHistoryError.IncompatibleApi)
    }

    @Test
    fun duplicateIdAcrossHiddenAndVisibleDoesNotConflict() {
        val body = base()
            .put(
                "data",
                JSONArray()
                    .put(message(1, "user", "Verborgen").put("display_kind", "hidden"))
                    .put(message(1, "assistant", "Sichtbar"))
            )
            .put("pagination", pagination(200, 0, 2))
        server.enqueue(jsonResponse(body))

        val result = fetch()

        assertTrue(result is MessageHistoryResult.Success)
        assertEquals(1, (result as MessageHistoryResult.Success).page.messages.size)
    }

    @Test
    fun rawAndUnknownFieldsAreIgnoredWithoutValidation() {
        val entry = message(1, "assistant", "Antwort")
            .put("tool_call_id", "tc-1")
            .put("tool_calls", JSONArray().put(JSONObject().put("name", "web_search")))
            .put("tool_name", "web_search")
            .put("timestamp", 1234)
            .put("token_count", 57)
            .put("finish_reason", "stop")
            .put("reasoning", "intern")
            .put("reasoning_content", "intern")
            .put("display_kind", "text")
        val body = base()
            .put("data", JSONArray().put(entry))
            .put("pagination", pagination(200, 0, 1))
        server.enqueue(jsonResponse(body))

        val result = fetch()

        assertTrue(result is MessageHistoryResult.Success)
        val message = (result as MessageHistoryResult.Success).page.messages.single()
        assertEquals("Antwort", message.content)
        assertEquals("web_search", message.toolName)
    }

    @Test
    fun explicitNullOptionalFieldsAreAcceptedAsAbsent() {
        val entry = message(1, "assistant", "Antwort")
            .put("timestamp", JSONObject.NULL)
            .put("tool_name", JSONObject.NULL)
            .put("display_kind", JSONObject.NULL)
        val body = base()
            .put("data", JSONArray().put(entry))
            .put("pagination", pagination(200, 0, 1))
        server.enqueue(jsonResponse(body))

        val result = fetch()

        assertTrue(result is MessageHistoryResult.Success)
        val message = (result as MessageHistoryResult.Success).page.messages.single()
        assertNull(message.timestampSeconds)
        assertNull(message.toolName)
    }

    @Test
    fun multimodalUserContentKeepsTheTextAndCountsImagesWithoutTheirData() {
        // Hermes stores a turn with an image as the part list it received.
        val parts = JSONArray()
            .put(JSONObject().put("type", "text").put("text", "Was ist das?"))
            .put(
                JSONObject().put("type", "image_url")
                    .put("image_url", JSONObject().put("url", "data:image/png;base64,SECRETPIXELS"))
            )
            .put(JSONObject().put("type", "refusal"))
        val entry = JSONObject()
            .put("id", 1)
            .put("session_id", "synthetic-id")
            .put("role", "user")
            .put("content", parts)
        server.enqueue(jsonResponse(pageWithMessage(entry)))

        val result = fetch()

        assertTrue(result is MessageHistoryResult.Success)
        val message = (result as MessageHistoryResult.Success).page.messages.single()
        assertEquals("Was ist das?", message.content)
        assertEquals(1, message.imageCount)
        assertFalse(result.toString().contains("SECRETPIXELS"))
    }

    @Test
    fun imageOnlyContentHasEmptyTextAndNonListContentStaysInvalid() {
        val imageOnly = JSONObject()
            .put("id", 1)
            .put("session_id", "synthetic-id")
            .put("role", "user")
            .put("content", JSONArray().put(JSONObject().put("type", "input_image")))
        server.enqueue(jsonResponse(pageWithMessage(imageOnly)))
        val message = ((fetch() as MessageHistoryResult.Success).page.messages.single())
        assertEquals("", message.content)
        assertEquals(1, message.imageCount)

        val numeric = message(1, "user", "x").put("content", 7)
        server.enqueue(jsonResponse(pageWithMessage(numeric)))
        assertFailure(fetch(), MessageHistoryError.IncompatibleApi)
    }

    @Test
    fun rootAndPaginationFieldsAreValidatedStrictly() {
        val oneEntry = JSONArray().put(message(1, "user", "x"))
        val invalidBodies = listOf(
            "not-json",
            base().put("object", "wrong"),
            base().remove("session_id"),
            base().put("session_id", 4),
            base().put("session_id", ""),
            base().put("data", "not-array"),
            base().remove("pagination"),
            base().put("pagination", "not-object"),
            base().put("pagination", pagination(200, 0, 0).put("limit", "200")),
            base().put("pagination", pagination(200, 0, 0).put("offset", 1.5)),
            base().put("pagination", pagination(200, 0, 0).put("order", 7)),
            base().put("pagination", pagination(200, 0, 0).put("order", "oldest")),
            base().put("pagination", pagination(200, 0, 0).put("returned", -1)),
            base().put("data", oneEntry).put("pagination", pagination(200, 0, 0))
        )

        invalidBodies.forEachIndexed { index, body ->
            server.enqueue(MockResponse().setBody(body.toString()))
            assertFailure(
                fetch(),
                MessageHistoryError.IncompatibleApi,
                "invalid root fixture $index"
            )
        }
    }

    @Test
    fun rowsWithARoleTheAppDoesNotShowAreSkippedLikeHiddenRows() {
        // Shape of a real page from the official core (Hermes 0.22+): a
        // `session_meta` row with null content closes every finished turn.
        server.enqueue(
            jsonResponse(
                base()
                    .put(
                        "data",
                        JSONArray()
                            .put(message(11, "user", "Frage").put("display_kind", "internal_notification"))
                            .put(message(12, "assistant", "").put("finish_reason", "tool_calls"))
                            .put(message(13, "tool", "").put("tool_name", "terminal"))
                            .put(message(14, "assistant", "Antwort").put("finish_reason", "stop"))
                            .put(
                                JSONObject()
                                    .put("id", 15)
                                    .put("session_id", "synthetic-id")
                                    .put("role", "session_meta")
                                    .put("content", JSONObject.NULL)
                            )
                            .put(message(16, "moderatoor", "nie sichtbar"))
                    )
                    .put("pagination", pagination(200, 0, 6))
            )
        )

        val result = fetch()

        assertTrue(result is MessageHistoryResult.Success)
        val page = (result as MessageHistoryResult.Success).page
        assertEquals(listOf(11L, 12L, 13L, 14L), page.messages.map { it.id })
        assertEquals("Frage", page.messages.first().content)
        // Skipped rows still count for the next offset.
        assertEquals(6, page.returned)
    }

    @Test
    fun messageFieldsAreValidatedStrictly() {
        val tooLarge = BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE)
        val invalidEntries = listOf(
            JSONObject().put("role", "user").put("content", "x"),
            JSONObject().put("id", "").put("role", "user").put("content", "x"),
            JSONObject().put("id", "not-a-number").put("role", "user").put("content", "x"),
            JSONObject().put("id", 0).put("role", "user").put("content", "x"),
            JSONObject().put("id", -1).put("role", "user").put("content", "x"),
            JSONObject().put("id", 1.5).put("role", "user").put("content", "x"),
            JSONObject().put("id", tooLarge).put("role", "user").put("content", "x"),
            JSONObject().put("id", 1).put("content", "x"),
            JSONObject().put("id", 1).put("role", 4).put("content", "x"),
            JSONObject().put("id", 1).put("role", "user"),
            JSONObject().put("id", 1).put("role", "user").put("content", JSONObject.NULL),
            JSONObject().put("id", 1).put("role", "user").put("content", 4),
            // A part list (even an empty one) is valid multimodal content; see
            // multimodalUserContentKeepsTheTextAndCountsImagesWithoutTheirData.
            JSONObject().put("id", 1).put("role", "user").put("content", JSONObject()),
            message(1, "tool", "x").put("tool_name", 4),
            message(1, "user", "x").put("timestamp", -1),
            message(1, "user", "x").put("timestamp", tooLarge),
            message(1, "user", "x").put("timestamp", "1000"),
            message(1, "user", "x").put("timestamp", true),
            message(1, "user", "x").put("timestamp", JSONArray()),
            message(1, "user", "x").put("display_kind", 4)
        )

        invalidEntries.forEachIndexed { index, entry ->
            server.enqueue(jsonResponse(pageWithMessage(entry)))
            assertFailure(
                fetch(),
                MessageHistoryError.IncompatibleApi,
                "invalid message fixture $index"
            )
        }
    }

    @Test
    fun finiteFractionalTimestampsAreAcceptedAndFlooredToWholeSeconds() {
        val body = base()
            .put(
                "data",
                JSONArray()
                    .put(message(1, "user", "Erste").put("timestamp", 1000.75))
                    .put(message(2, "assistant", "Zweite").put("timestamp", 0.5))
                    .put(message(3, "system", "Dritte").put("timestamp", 1788000000.999))
                    .put(message(4, "tool", "Vierte").put("timestamp", 1000.0))
            )
            .put("pagination", pagination(200, 0, 4))
        server.enqueue(jsonResponse(body))

        val result = fetch() as MessageHistoryResult.Success
        assertEquals(
            listOf(1000L, 0L, 1788000000L, 1000L),
            result.page.messages.map { it.timestampSeconds }
        )
    }

    @Test
    fun emptyTextContentRemainsAValidVisibleMessage() {
        server.enqueue(jsonResponse(pageWithMessage(message(1, "assistant", ""))))

        val result = fetch() as MessageHistoryResult.Success

        assertEquals("", result.page.messages.single().content)
    }

    @Test
    fun nonFiniteTimestampLiteralsAreRejectedAsIncompatible() {
        val raw = """{"object":"list","session_id":"synthetic-id","data":[{"id":1,"role":"user","content":"x","timestamp":NaN}],"pagination":{"limit":200,"offset":0,"order":"latest","returned":1}}"""
        server.enqueue(MockResponse().setBody(raw))

        assertFailure(fetch(), MessageHistoryError.IncompatibleApi)
    }

    @Test
    fun authenticationNotFoundRateLimitAndHttpErrorsAreClosedAndNotRetried() {
        listOf(
            401 to MessageHistoryError.InvalidCredentials,
            403 to MessageHistoryError.InvalidCredentials,
            404 to MessageHistoryError.SessionNotFound,
            429 to MessageHistoryError.RateLimited,
            500 to MessageHistoryError.Http,
            503 to MessageHistoryError.Http
        ).forEach { (code, reason) ->
            server.enqueue(MockResponse().setResponseCode(code))
            assertFailure(fetch(), reason)
        }
        assertEquals(6, server.requestCount)
    }

    @Test
    fun redirectIsNotFollowed() {
        server.enqueue(
            MockResponse().setResponseCode(302)
                .addHeader("Location", server.url("/redirect-target"))
        )
        server.enqueue(jsonResponse(validPage()))

        assertFailure(fetch(), MessageHistoryError.Http)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun oneMebibytePageIsReadButBodiesAboveEightMebibytesAreRejectedFailClosed() {
        // A real Telegram session with tool output delivered 776 233 bytes,
        // which the old 512 KiB ceiling discarded. One MiB must now be read.
        val largeContent = "a".repeat(MEBIBYTE)
        val body = base()
            .put("data", JSONArray().put(message(1, "assistant", largeContent)))
            .put("pagination", pagination(200, 0, 1))
        server.enqueue(jsonResponse(body))

        val result = fetch() as MessageHistoryResult.Success
        assertEquals(MEBIBYTE, result.page.messages.single().content.length)

        val oversized = "a".repeat(MAX_RESPONSE_BYTES + 1)
        server.enqueue(MockResponse().setBody(oversized))
        assertFailure(fetch(), MessageHistoryError.IncompatibleApi)

        server.enqueue(MockResponse().setChunkedBody(oversized, 1024))
        assertFailure(fetch(), MessageHistoryError.IncompatibleApi)
    }

    @Test
    fun latestPageKeepsHermesChronologicalOrder() {
        // Hermes get_messages(..., latest=True) selects the newest rows in
        // descending id order and reverses them back into chronological
        // insertion order (hermes_state_messages.py, api_server.py), so the
        // client must pass that order through unchanged - no local reversal.
        val newestIds = 801L..804L
        val data = JSONArray().apply {
            newestIds.forEach { id -> put(message(id, "user", "Nachricht $id")) }
        }
        server.enqueue(
            jsonResponse(
                base()
                    .put("data", data)
                    .put("pagination", pagination(200, 0, data.length()))
            )
        )

        val result = fetch() as MessageHistoryResult.Success

        assertEquals(newestIds.toList(), result.page.messages.map { it.id })
    }

    @Test
    fun timeoutTlsAndNetworkFailuresAreMappedWithoutDetails() {
        val timeoutClient = trustedClient.newBuilder()
            .readTimeout(100, TimeUnit.MILLISECONDS)
            .callTimeout(300, TimeUnit.MILLISECONDS)
            .build()
        server.enqueue(jsonResponse(validPage()).setBodyDelay(500, TimeUnit.MILLISECONDS))
        assertFailure(
            HermesMessageClient(timeoutClient).fetch(baseUrl(), SecretValue(FIXTURE_KEY), SESSION_ID, 0),
            MessageHistoryError.Timeout
        )

        val untrusted = HermesMessageClient(OkHttpClient.Builder().build())
        assertFailure(
            untrusted.fetch(baseUrl(), SecretValue(FIXTURE_KEY), SESSION_ID, 0),
            MessageHistoryError.Tls
        )

        val stopped = MockWebServer().apply { start() }
        val unavailable = stopped.url("/").toString().replace("http://", "https://")
        stopped.shutdown()
        val networkResult = client.fetch(unavailable, SecretValue(FIXTURE_KEY), SESSION_ID, 0)
        assertTrue(networkResult is MessageHistoryResult.Failure)
        assertTrue(
            (networkResult as MessageHistoryResult.Failure).error in
                setOf(MessageHistoryError.Network, MessageHistoryError.Tls)
        )
        assertFalse(networkResult.toString().contains(unavailable))
        assertFalse(networkResult.toString().contains(FIXTURE_KEY))
    }

    @Test
    fun unsafeLocalInputsCreateNoRequest() {
        val invalidCalls = listOf(
            Triple(baseUrl(), SecretValue(FIXTURE_KEY), ""),
            Triple(baseUrl(), SecretValue(FIXTURE_KEY), "   "),
            Triple("http://example.invalid", SecretValue(FIXTURE_KEY), SESSION_ID),
            Triple("https://user@example.invalid", SecretValue(FIXTURE_KEY), SESSION_ID),
            Triple(baseUrl(), SecretValue(""), SESSION_ID),
            Triple(baseUrl(), SecretValue("fixture credential"), SESSION_ID),
            Triple(baseUrl(), SecretValue("fixture\ncredential"), SESSION_ID),
            Triple(baseUrl(), SecretValue("k".repeat(4097)), SESSION_ID)
        )

        invalidCalls.forEach { (url, key, sessionId) ->
            assertFailure(
                client.fetch(url, key, sessionId, 0),
                MessageHistoryError.InvalidInput
            )
        }
        assertEquals(0, server.requestCount)
    }

    private fun fetch(
        sessionId: String = SESSION_ID,
        offset: Int = 0
    ): MessageHistoryResult =
        client.fetch(baseUrl(), SecretValue(FIXTURE_KEY), sessionId, offset)

    private fun baseUrl(): String = server.url("/").toString().trimEnd('/')

    private fun base(): JSONObject = JSONObject()
        .put("object", "list")
        .put("session_id", "synthetic-id")
        .put("data", JSONArray())
        .put("pagination", pagination(200, 0, 0))

    private fun validPage(): JSONObject = base()

    private fun pagination(limit: Int, offset: Int, returned: Int): JSONObject = JSONObject()
        .put("limit", limit)
        .put("offset", offset)
        .put("order", "latest")
        .put("returned", returned)

    private fun message(id: Long, role: String, content: String): JSONObject = JSONObject()
        .put("id", id)
        .put("session_id", "synthetic-id")
        .put("role", role)
        .put("content", content)

    private fun pageWithMessage(entry: JSONObject): JSONObject = base()
        .put("data", JSONArray().put(entry))
        .put("pagination", pagination(200, 0, 1))

    private fun jsonResponse(body: JSONObject): MockResponse = MockResponse()
        .addHeader("Content-Type", "application/json")
        .setBody(body.toString())

    private fun assertFailure(
        result: MessageHistoryResult,
        expected: MessageHistoryError,
        message: String = "expected closed failure"
    ) {
        assertTrue(message, result is MessageHistoryResult.Failure)
        assertEquals(expected, (result as MessageHistoryResult.Failure).error)
        assertFalse(result.toString().contains(FIXTURE_KEY))
    }

    private companion object {
        const val FIXTURE_KEY = "fixture-credential"
        const val SESSION_ID = "synthetic-id"
        const val MEBIBYTE = 1024 * 1024
        const val MAX_RESPONSE_BYTES = 8 * MEBIBYTE
    }
}
