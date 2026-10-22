package io.github.excludebarrier.orsikrelay.network

import io.github.excludebarrier.orsikrelay.domain.message.MessageHistoryError
import io.github.excludebarrier.orsikrelay.domain.message.MessageHistoryResult
import io.github.excludebarrier.orsikrelay.domain.message.MessagePage
import io.github.excludebarrier.orsikrelay.domain.message.MessageRole
import io.github.excludebarrier.orsikrelay.domain.profile.SecretValue
import java.math.BigInteger
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Adversarial history pages for [HermesMessageClient]: mixed content types,
 * hidden and tool rows carrying private payloads, unicode and control
 * characters, exact numeric bounds and strict UTF-8.
 */
class HermesMessageClientEdgeCaseTest {

    private lateinit var server: MockWebServer
    private lateinit var client: HermesMessageClient

    @Before
    fun setUp() {
        val certificate = HeldCertificate.Builder()
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
        client = HermesMessageClient(
            OkHttpClient.Builder()
                .sslSocketFactory(
                    clientCertificates.sslSocketFactory(),
                    clientCertificates.trustManager
                )
                .build()
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun mixedContentListJoinsTextPartsAndCountsImagesWithoutTheirData() {
        val parts = JSONArray()
            .put("Bare string part")
            .put(JSONObject().put("type", "text").put("text", "Text eins"))
            .put(JSONObject().put("type", "input_text").put("text", "Text zwei"))
            .put(
                JSONObject().put("type", "image_url")
                    .put(
                        "image_url",
                        JSONObject().put("url", "data:image/png;base64,SECRETPIXELS")
                    )
            )
            .put(JSONObject().put("type", "image"))
            .put(JSONObject().put("type", "input_image"))
            .put(JSONObject().put("type", "refusal"))
            .put(JSONObject.NULL)
            .put(42)
            .put(JSONArray().put("nested"))
            .put(JSONObject().put("type", "text").put("text", ""))
            .put(JSONObject().put("type", "text").put("text", 7))
            .put(JSONObject().put("type", "text").put("text", "Text drei"))

        server.enqueue(jsonResponse(pageWithRow(row(1, "user", parts))))

        val message = fetchPage().messages.single()

        assertEquals(
            "Bare string part\n\nText eins\n\nText zwei\n\nText drei",
            message.content
        )
        assertEquals(3, message.imageCount)
        assertFalse(message.toString().contains("SECRETPIXELS"))
    }

    @Test
    fun anEmptyContentListIsAValidEmptyVisibleMessage() {
        server.enqueue(jsonResponse(pageWithRow(row(1, "assistant", JSONArray()))))

        val message = fetchPage().messages.single()

        assertEquals("", message.content)
        assertEquals(0, message.imageCount)
    }

    @Test
    fun nullOrWrongTypedContentOnAVisibleRowIsIncompatible() {
        listOf<Any>(
            JSONObject.NULL,
            7,
            true,
            JSONObject()
        ).forEachIndexed { index, content ->
            server.enqueue(jsonResponse(pageWithRow(row(1, "user", content))))
            assertFailure(
                client.fetch(baseUrl(), SecretValue(FIXTURE_KEY), SESSION_ID, 0),
                MessageHistoryError.IncompatibleApi,
                "content fixture $index"
            )
        }
    }

    @Test
    fun hiddenRowsAreDroppedBeforeTheirContentAndIdAreValidated() {
        val hidden = JSONObject()
            .put("session_id", SESSION_ID)
            .put("role", "user")
            .put("content", JSONObject.NULL)
            .put("display_kind", "hidden")
        val body = base()
            .put("data", JSONArray().put(hidden))
            .put("pagination", pagination(200, 0, 1))
        server.enqueue(jsonResponse(body))

        val page = fetchPage()

        assertTrue(page.messages.isEmpty())
        assertEquals(1, page.returned)
    }

    @Test
    fun toolRowsNeverSurfaceRawToolCallsArgumentsOutputOrReasoning() {
        val toolRow = row(7, "tool", "Ergebnis")
            .put("tool_name", "terminal")
            .put("tool_call_id", "call-SECRET-ID")
            .put(
                "tool_calls",
                JSONArray().put(
                    JSONObject().put("id", "call-SECRET-ID")
                        .put(
                            "function",
                            JSONObject()
                                .put("name", "terminal")
                                .put("arguments", "{\"cmd\":\"SECRET-ARGS\"}")
                        )
                )
            )
            .put("args", JSONObject().put("command", "SECRET-CMD"))
            .put("output", "SECRET-OUTPUT")
            .put("raw", JSONObject().put("payload", "SECRET-RAW"))
            .put("reasoning", "SECRET-REASONING")
            .put("reasoning_content", "SECRET-THOUGHT")
            .put("sigma", "SECRET-SIGMA")
        server.enqueue(jsonResponse(pageWithRow(toolRow)))

        val message = fetchPage().messages.single()

        assertEquals(MessageRole.TOOL, message.role)
        assertEquals("Ergebnis", message.content)
        assertEquals("terminal", message.toolName)
        val rendered = message.toString()
        listOf(
            "SECRET-ID",
            "SECRET-ARGS",
            "SECRET-CMD",
            "SECRET-OUTPUT",
            "SECRET-RAW",
            "SECRET-REASONING",
            "SECRET-THOUGHT",
            "SECRET-SIGMA",
            "tool_calls"
        ).forEach { marker ->
            assertFalse("payload marker $marker leaked", rendered.contains(marker))
        }
    }

    @Test
    fun unicodeTextIsPreservedAndControlCharactersAreRejectedInToolNames() {
        val unicode = "Hä 😀 中文 مرحبا e\u0301\nzweite Zeile\tdritter Teil"
        server.enqueue(
            jsonResponse(
                pageWithRow(row(1, "assistant", unicode).put("tool_name", "terminal_ü"))
            )
        )
        val message = fetchPage().messages.single()
        assertEquals(unicode, message.content)
        assertEquals("terminal_ü", message.toolName)

        listOf("terminal\u0000x", "terminal\u001fx", "terminal\u007fx", "terminal\u0085x")
            .forEach { unsafeToolName ->
                server.enqueue(
                    jsonResponse(
                        pageWithRow(row(1, "tool", "x").put("tool_name", unsafeToolName))
                    )
                )
                assertFailure(
                    client.fetch(baseUrl(), SecretValue(FIXTURE_KEY), SESSION_ID, 0),
                    MessageHistoryError.IncompatibleApi,
                    "tool_name fixture with control character"
                )
            }
    }

    @Test
    fun sessionIdCarryingAControlCharacterIsRejected() {
        val body = base()
            .put("session_id", "synthetic\u0000-id")
            .put("data", JSONArray())
            .put("pagination", pagination(200, 0, 0))
        server.enqueue(jsonResponse(body))

        assertFailure(fetch(), MessageHistoryError.IncompatibleApi)
    }

    @Test
    fun paginationAcceptsTheIntBoundAndRejectsValuesBeyondIt() {
        server.enqueue(
            jsonResponse(
                base()
                    .put("data", JSONArray())
                    .put("pagination", pagination(Int.MAX_VALUE, 0, 0))
            )
        )
        assertEquals(Int.MAX_VALUE, fetchPage().limit)

        listOf(
            base()
                .put("data", JSONArray())
                .put("pagination", pagination(0, 0, 0).put("limit", 2147483648L)),
            base()
                .put("data", JSONArray())
                .put(
                    "pagination",
                    pagination(0, 0, 0).put("offset", BigInteger.ONE.shiftLeft(100))
                )
        ).forEachIndexed { index, body ->
            server.enqueue(jsonResponse(body))
            assertFailure(fetch(), MessageHistoryError.IncompatibleApi, "bound fixture $index")
        }

        // An integer written with a zero fraction is still an exact integer.
        server.enqueue(
            jsonResponse(
                base()
                    .put("data", JSONArray().put(row(1, "user", "x")))
                    .put("pagination", pagination(0, 0, 1).put("returned", 1.0))
            )
        )
        assertEquals(1, fetchPage().returned)
    }

    @Test
    fun timestampBoundsAreExactAndHugeExponentIsRejected() {
        val body = base()
            .put(
                "data",
                JSONArray()
                    .put(row(1, "user", "zero").put("timestamp", 0))
                    .put(row(2, "user", "fraction").put("timestamp", 0.999))
                    .put(row(3, "user", "max").put("timestamp", Long.MAX_VALUE))
            )
            .put("pagination", pagination(200, 0, 3))
        server.enqueue(jsonResponse(body))

        assertEquals(
            listOf(0L, 0L, Long.MAX_VALUE),
            fetchPage().messages.map { it.timestampSeconds }
        )

        val raw = """{"object":"list","session_id":"$SESSION_ID","data":[""" +
            """{"id":1,"role":"user","content":"x","timestamp":1e400}],""" +
            """"pagination":{"limit":200,"offset":0,"order":"latest","returned":1}}"""
        server.enqueue(MockResponse().addHeader("Content-Type", "application/json").setBody(raw))
        assertFailure(fetch(), MessageHistoryError.IncompatibleApi)

        server.enqueue(
            jsonResponse(pageWithRow(row(1, "user", "x").put("timestamp", -0.5)))
        )
        assertFailure(fetch(), MessageHistoryError.IncompatibleApi)
    }

    @Test
    fun aBodyWithInvalidUtf8IsIncompatibleEvenThoughTheJsonWouldParse() {
        // A complete, valid JSON page followed by one invalid UTF-8 byte.
        // A lenient decoder would substitute U+FFFD (which the JSON object
        // parser ignores after the closing brace) and accept the page.
        val body = okio.Buffer()
            .writeUtf8(base().put("data", JSONArray()).toString())
            .writeByte(0xFF)
        server.enqueue(
            MockResponse()
                .addHeader("Content-Type", "application/json")
                .setChunkedBody(body, 1024)
        )

        assertFailure(fetch(), MessageHistoryError.IncompatibleApi)
    }

    private fun fetch(): MessageHistoryResult =
        client.fetch(baseUrl(), SecretValue(FIXTURE_KEY), SESSION_ID, 0)

    private fun fetchPage(): MessagePage =
        (fetch() as MessageHistoryResult.Success).page

    private fun baseUrl(): String = server.url("/").toString().trimEnd('/')

    private fun base(): JSONObject = JSONObject()
        .put("object", "list")
        .put("session_id", SESSION_ID)
        .put("data", JSONArray())
        .put("pagination", pagination(200, 0, 0))

    private fun pagination(limit: Int, offset: Int, returned: Int): JSONObject = JSONObject()
        .put("limit", limit)
        .put("offset", offset)
        .put("order", "latest")
        .put("returned", returned)

    private fun row(id: Long, role: String, content: Any): JSONObject = JSONObject()
        .put("id", id)
        .put("session_id", SESSION_ID)
        .put("role", role)
        .put("content", content)

    private fun pageWithRow(entry: JSONObject): JSONObject = base()
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
    }
}
