package io.github.excludebarrier.orsikrelay.network

import io.github.excludebarrier.orsikrelay.data.message.StreamingChatRepository
import io.github.excludebarrier.orsikrelay.data.profile.ServerProfileMetadataStore
import io.github.excludebarrier.orsikrelay.data.profile.ServerProfileRepository
import io.github.excludebarrier.orsikrelay.domain.chat.ChatOutboundMessage
import io.github.excludebarrier.orsikrelay.domain.chat.ChatStreamError
import io.github.excludebarrier.orsikrelay.domain.chat.ChatStreamHandle
import io.github.excludebarrier.orsikrelay.domain.chat.ChatStreamListener
import io.github.excludebarrier.orsikrelay.domain.chat.ChatStreamResumeState
import io.github.excludebarrier.orsikrelay.domain.chat.ChatTurnEvent
import io.github.excludebarrier.orsikrelay.domain.profile.SecretValue
import io.github.excludebarrier.orsikrelay.domain.profile.ServerProfile
import io.github.excludebarrier.orsikrelay.security.credential.CredentialStore
import java.io.InputStream
import java.util.concurrent.CountDownLatch
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
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HermesSessionStreamClientTest {

    private lateinit var server: MockWebServer
    private lateinit var certificate: HeldCertificate
    private lateinit var trustedClient: OkHttpClient
    private lateinit var client: HermesSessionStreamClient

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
        // MockWebServer 4.12 negotiates ALPN to h2, but its h2 handler
        // writes the HTTP/1.1 chunked framing into the DATA payload, which
        // corrupts the SSE body. Pin the fixture to HTTP/1.1; the
        // production client keeps its default protocol list.
        trustedClient = OkHttpClient.Builder()
            .sslSocketFactory(
                clientCertificates.sslSocketFactory(),
                clientCertificates.trustManager
            )
            .protocols(listOf(Protocol.HTTP_1_1))
            .build()
        client = HermesSessionStreamClient(trustedClient)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun successfulStreamSendsExactPathHeadersAndBodyOnlyMessage() {
        server.enqueue(sseResponse(successfulStreamFrames(ENCODED_SESSION_ID)))

        val listener = RecordingListener()
        val handle = client.openTurn(
            baseUrl = server.url("/p/android").toString().trimEnd('/'),
            apiKey = SecretValue(FIXTURE_KEY),
            sessionId = ENCODED_SESSION_ID,
            message = "  Hallo Welt  ",
            listener = listener
        )
        assertTrue("stream did not finish", listener.finished.await(10, TimeUnit.SECONDS))
        handle.close()
        handle.close()

        val request = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) {
            "expected one recorded request"
        }
        assertEquals("POST", request.method)
        assertEquals(
            "/p/android/api/sessions/synthetic%2Fsess%20ion/chat/stream",
            request.requestUrl!!.encodedPath
        )
        assertNull(request.requestUrl!!.query)
        assertEquals("Bearer $FIXTURE_KEY", request.getHeader("Authorization"))
        assertEquals("application/json", request.getHeader("Content-Type"))
        assertEquals("text/event-stream", request.getHeader("Accept"))
        assertNull(request.getHeader("X-Hermes-Interactive-Approvals"))
        val body = JSONObject(request.body.readUtf8())
        assertEquals(1, body.length())
        assertEquals("Hallo Welt", body.getString("message"))
        assertFalse(request.requestUrl!!.toString().contains(FIXTURE_KEY))
        assertEquals(
            listOf<ChatTurnEvent>(
                ChatTurnEvent.RunStarted(RUN_ID),
                ChatTurnEvent.AssistantStarted,
                ChatTurnEvent.AssistantDelta("Hallo"),
                ChatTurnEvent.AssistantCompleted("Hallo Welt"),
                ChatTurnEvent.TurnCompleted()
            ),
            listener.events
        )
    }

    @Test
    fun negotiatedApprovalCapabilityAddsOptInHeader() {
        server.enqueue(sseResponse(successfulStreamFrames(SESSION_ID)))
        val listener = RecordingListener()

        client.openTurn(
            baseUrl = baseUrl(),
            apiKey = SecretValue(FIXTURE_KEY),
            sessionId = SESSION_ID,
            message = "Hallo",
            interactiveApprovals = true,
            listener = listener
        )
        assertTrue(listener.finished.await(10, TimeUnit.SECONDS))

        val request = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertEquals("1", request.getHeader("X-Hermes-Interactive-Approvals"))
    }

    @Test
    fun resumeRequestCarriesTheApprovalOptInHeaderOnlyWhenNegotiated() {
        // The re-attached pipeline accepts approval.request, so the
        // re-attach connection must declare the same protocol-v1 opt-in
        // as the initial POST - and must never declare it when the
        // capability was not negotiated.
        listOf(true, false).forEach { interactive ->
            // The fixture is deliberately an EMPTY event stream: the test
            // asserts the request shape only, and an empty body is a valid
            // (if fruitless) SSE response - it never pretends to be a valid
            // resumed turn.
            server.enqueue(
                MockResponse()
                    .addHeader("Content-Type", "text/event-stream")
                    .setChunkedBody("", 0)
            )
            val listener = RecordingListener()
            client.openResume(
                baseUrl = baseUrl(),
                apiKey = SecretValue(FIXTURE_KEY),
                sessionId = SESSION_ID,
                runId = RUN_ID,
                resume = resumeState(lastSeq = 2L),
                interactiveApprovals = interactive,
                listener = listener
            )
            assertTrue(listener.finished.await(10, TimeUnit.SECONDS))
            val request = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) {
                "expected one recorded resume request"
            }
            assertEquals("GET", request.method)
            assertEquals(
                if (interactive) "1" else null,
                request.getHeader("X-Hermes-Interactive-Approvals")
            )
            assertEquals("2", request.requestUrl!!.queryParameter("after_seq"))
        }
    }

    @Test
    fun keyNeverAppearsInUrlBodyOrEvents() {
        server.enqueue(sseResponse(successfulStreamFrames(SESSION_ID)))
        val listener = RecordingListener()
        client.openTurn(baseUrl(), SecretValue(FIXTURE_KEY), SESSION_ID, "Hallo", listener)
        assertTrue(listener.finished.await(10, TimeUnit.SECONDS))

        val request = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) {
            "expected one recorded request"
        }
        assertFalse(request.requestUrl!!.toString().contains(FIXTURE_KEY))
        assertFalse(request.body.readUtf8().contains(FIXTURE_KEY))
        listener.events.forEach { event ->
            assertFalse(event.toString().contains(FIXTURE_KEY))
        }
    }

    @Test
    fun httpStatusesMapToClosedErrorsWithoutRetry() {
        listOf(
            401 to ChatStreamError.InvalidCredentials,
            403 to ChatStreamError.InvalidCredentials,
            404 to ChatStreamError.SessionNotFound,
            409 to ChatStreamError.Conflict,
            429 to ChatStreamError.RateLimited,
            500 to ChatStreamError.Http,
            503 to ChatStreamError.Http
        ).forEach { (code, reason) ->
            server.enqueue(MockResponse().setResponseCode(code))
            val listener = RecordingListener()
            client.openTurn(baseUrl(), SecretValue(FIXTURE_KEY), "plain-id", "Hallo", listener)
            assertTrue("stream for $code did not finish", listener.finished.await(10, TimeUnit.SECONDS))
            assertEquals(
                listOf<ChatTurnEvent>(ChatTurnEvent.TurnFailed(reason)),
                listener.events
            )
            assertFalse(listener.events.toString().contains(FIXTURE_KEY))
        }
        // One request per failure: no automatic retry.
        assertEquals(7, server.requestCount)
    }

    @Test
    fun resumeRequestCarriesAfterSeqAndStatusFailuresStayClosed() {
        // P4 re-attach: the GET is addressed by session + run and resumes
        // exactly after the confirmed sequence; every status failure is
        // closed and never retried inside the transport. A 5xx is the one
        // transient status (the run is still alive server-side) - and a
        // status failure never binds a stream, so the handle has no resume
        // state and the repository continues from the state it started from.
        listOf(
            401 to ChatStreamError.InvalidCredentials,
            403 to ChatStreamError.InvalidCredentials,
            404 to ChatStreamError.ResumeUnavailable,
            409 to ChatStreamError.ResumeGap,
            429 to ChatStreamError.RateLimited,
            500 to ChatStreamError.Network,
            503 to ChatStreamError.Network
        ).forEach { (code, reason) ->
            server.enqueue(MockResponse().setResponseCode(code))
            val listener = RecordingListener()
            val handle = client.openResume(
                baseUrl = baseUrl(),
                apiKey = SecretValue(FIXTURE_KEY),
                sessionId = SESSION_ID,
                runId = RUN_ID,
                resume = resumeState(lastSeq = 2L),
                listener = listener
            )
            assertTrue(
                "resume for $code did not finish",
                listener.finished.await(10, TimeUnit.SECONDS)
            )
            assertEquals(
                listOf<ChatTurnEvent>(ChatTurnEvent.TurnFailed(reason)),
                listener.events
            )
            assertFalse(listener.events.toString().contains(FIXTURE_KEY))
            assertNull(handle.resumeState())
            handle.close()

            val request = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) {
                "expected one recorded request"
            }
            assertEquals("GET", request.method)
            assertEquals(
                "/api/sessions/$SESSION_ID/runs/$RUN_ID/stream",
                request.requestUrl!!.encodedPath
            )
            assertEquals("2", request.requestUrl!!.queryParameter("after_seq"))
            assertEquals("Bearer $FIXTURE_KEY", request.getHeader("Authorization"))
            assertEquals("text/event-stream", request.getHeader("Accept"))
            assertFalse(request.requestUrl!!.toString().contains(FIXTURE_KEY))
        }
        // One request per failure: the re-attach ladder lives in the
        // repository, never in the transport.
        assertEquals(7, server.requestCount)
    }

    @Test
    fun cleanMidStreamEofWithoutTerminalFrameIsNetworkNotIncompatible() {
        // P4 finding: a socket that dies cleanly at a frame boundary
        // without any terminal frame is a transient disconnect, not a
        // protocol violation. The classified reason must be the
        // reconnectable ChatStreamError.Network, never
        // IncompatibleApi and never a fabricated TurnCompleted.
        server.enqueue(
            sseResponse(
                listOf(
                    frame("run.started", 1, JSONObject(), SESSION_ID),
                    frame("message.started", 2, messageExtra(), SESSION_ID),
                    frame("assistant.delta", 3, deltaExtra("Hallo"), SESSION_ID)
                )
            )
        )
        val listener = RecordingListener()
        client.openTurn(baseUrl(), SecretValue(FIXTURE_KEY), SESSION_ID, "Hallo", listener)
        assertTrue(listener.finished.await(10, TimeUnit.SECONDS))
        assertEquals(
            listOf<ChatTurnEvent>(
                ChatTurnEvent.RunStarted(RUN_ID),
                ChatTurnEvent.AssistantStarted,
                ChatTurnEvent.AssistantDelta("Hallo"),
                ChatTurnEvent.TurnFailed(ChatStreamError.Network)
            ),
            listener.events
        )
        assertFalse(listener.events.any { it is ChatTurnEvent.TurnCompleted })
        assertEquals(1, server.requestCount)
    }

    @Test
    fun resumeWithForeignRunIdFailsClosedWithoutSendingARequest() {
        // P4 run-id boundary: the confirmed snapshot of a DIFFERENT run
        // must never be used to build a re-attach request. No request
        // is sent (the MockWebServer has no queued response at all) and
        // the turn fails closed with a definitive reason, so the
        // repository neither retries nor re-attaches a foreign run.
        val listener = RecordingListener()
        val handle = client.openResume(
            baseUrl = baseUrl(),
            apiKey = SecretValue(FIXTURE_KEY),
            sessionId = SESSION_ID,
            runId = RUN_ID,
            resume = resumeState(lastSeq = 2L).copy(runId = "run_other_synthetic"),
            listener = listener
        )
        assertTrue(listener.finished.await(10, TimeUnit.SECONDS))
        assertEquals(
            listOf<ChatTurnEvent>(ChatTurnEvent.TurnFailed(ChatStreamError.InvalidInput)),
            listener.events
        )
        assertNull(handle.resumeState())
        assertEquals(0, server.requestCount)
        handle.close()
    }

    @Test
    fun textOnlyOpenTurnWithAttachmentsSendsExactLegacyBody() {
        // P5 wire exactness: a text-only body must stay byte-identical to
        // the pre-P5 legacy shape - no content-part list, no extra field.
        server.enqueue(sseResponse(successfulStreamFrames(SESSION_ID)))
        val listener = RecordingListener()

        client.openTurnWithAttachments(
            baseUrl = baseUrl(),
            apiKey = SecretValue(FIXTURE_KEY),
            sessionId = SESSION_ID,
            message = checkNotNull(ChatOutboundMessage.textOnly("hallo")),
            interactiveApprovals = false,
            listener = listener
        )
        assertTrue(listener.finished.await(10, TimeUnit.SECONDS))

        val request = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) {
            "expected one recorded request"
        }
        assertEquals("POST", request.method)
        assertEquals("""{"message":"hallo"}""", request.body.readUtf8())
        assertEquals(1, server.requestCount)
        assertEquals(
            listOf<ChatTurnEvent>(
                ChatTurnEvent.RunStarted(RUN_ID),
                ChatTurnEvent.AssistantStarted,
                ChatTurnEvent.AssistantDelta("Hallo"),
                ChatTurnEvent.AssistantCompleted("Hallo Welt"),
                ChatTurnEvent.TurnCompleted()
            ),
            listener.events
        )
    }

    @Test
    fun imageOnlyOpenTurnWithAttachmentsSendsSingleImagePartWithNoLocalPaths() {
        // P5 wire exactness: an image-only turn is a content-part list with
        // exactly one `image_url` part carrying the inline data URL; no
        // `content://` reference, no `file://` path and no file name ever
        // reach the wire.
        server.enqueue(sseResponse(successfulStreamFrames(SESSION_ID)))
        val listener = RecordingListener()

        client.openTurnWithAttachments(
            baseUrl = baseUrl(),
            apiKey = SecretValue(FIXTURE_KEY),
            sessionId = SESSION_ID,
            message = checkNotNull(ChatOutboundMessage.create("", listOf(IMAGE_DATA_URL))),
            interactiveApprovals = false,
            listener = listener
        )
        assertTrue(listener.finished.await(10, TimeUnit.SECONDS))

        val request = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) {
            "expected one recorded request"
        }
        assertEquals("POST", request.method)
        val raw = request.body.readUtf8()
        val parts = JSONObject(raw).getJSONArray("message")
        assertEquals(1, parts.length())
        val part = parts.getJSONObject(0)
        assertEquals(2, part.length())
        assertEquals("image_url", part.getString("type"))
        val imageUrl = part.getJSONObject("image_url")
        assertEquals(1, imageUrl.length())
        val url = imageUrl.getString("url")
        assertEquals(IMAGE_DATA_URL, url)
        assertTrue(url.startsWith("data:image/png;base64,"))
        assertFalse(raw.contains("content://"))
        assertFalse(raw.contains("file://"))
        assertFalse(raw.contains(ATTACHMENT_FILE_NAME))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun textAndImageOpenTurnWithAttachmentsSendsOrderedTwoPartList() {
        // P5 wire exactness: with text and an image the body is a
        // two-part list - text first, image second - and each appears
        // exactly once.
        server.enqueue(sseResponse(successfulStreamFrames(SESSION_ID)))
        val listener = RecordingListener()

        client.openTurnWithAttachments(
            baseUrl = baseUrl(),
            apiKey = SecretValue(FIXTURE_KEY),
            sessionId = SESSION_ID,
            message = checkNotNull(
                ChatOutboundMessage.create("  schau das  ", listOf(IMAGE_DATA_URL))
            ),
            interactiveApprovals = false,
            listener = listener
        )
        assertTrue(listener.finished.await(10, TimeUnit.SECONDS))

        val request = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) {
            "expected one recorded request"
        }
        assertEquals("POST", request.method)
        val raw = request.body.readUtf8()
        val parts = JSONObject(raw).getJSONArray("message")
        assertEquals(2, parts.length())
        val textPart = parts.getJSONObject(0)
        assertEquals("text", textPart.getString("type"))
        assertEquals("schau das", textPart.getString("text"))
        val imagePart = parts.getJSONObject(1)
        assertEquals("image_url", imagePart.getString("type"))
        assertEquals(
            IMAGE_DATA_URL,
            imagePart.getJSONObject("image_url").getString("url")
        )
        assertEquals(1, raw.split("schau das").size - 1)
        assertEquals(1, raw.split(IMAGE_DATA_URL).size - 1)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun foreignImageDataUrlFailsClosedWithoutSendingARequest() {
        // P5 wire exactness: a data URL that is not an inline image
        // (`data:image/...`) must never reach the wire. The defensive
        // re-validation in the transport rejects it before any request is
        // built, so the server never sees the turn at all.
        val listener = RecordingListener()
        val handle = client.openTurnWithAttachments(
            baseUrl = baseUrl(),
            apiKey = SecretValue(FIXTURE_KEY),
            sessionId = SESSION_ID,
            message = ChatOutboundMessage("hallo", listOf("data:text/plain;base64,AA==")),
            interactiveApprovals = false,
            listener = listener
        )
        assertTrue(listener.finished.await(10, TimeUnit.SECONDS))
        assertEquals(
            listOf<ChatTurnEvent>(ChatTurnEvent.TurnFailed(ChatStreamError.InvalidInput)),
            listener.events
        )
        assertEquals(0, server.requestCount)
        handle.close()
    }

    @Test
    fun legacyOpenTurnStillSendsExactLegacyBody() {
        // P5 regression guard: the pre-P5 string path must keep sending
        // the exact legacy body, unchanged.
        server.enqueue(sseResponse(successfulStreamFrames(SESSION_ID)))
        val listener = RecordingListener()

        client.openTurn(
            baseUrl(), SecretValue(FIXTURE_KEY), SESSION_ID, "hallo", listener
        )
        assertTrue(listener.finished.await(10, TimeUnit.SECONDS))

        val request = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) {
            "expected one recorded request"
        }
        assertEquals("POST", request.method)
        assertEquals("""{"message":"hallo"}""", request.body.readUtf8())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun productionConnectAndWriteTimeoutsAreFiniteWhileAStreamStaysLongLived() {
        // P4 wallclock decision: the established SSE stream must stay
        // long-lived (no global call timeout), but every connect and
        // write of a broken (re-)attach attempt must be bounded.
        val defaults = HermesSessionStreamClient.newDefaultStreamClient()
        assertTrue("connect timeout must be finite", defaults.connectTimeoutMillis > 0)
        assertTrue("write timeout must be finite", defaults.writeTimeoutMillis > 0)
        assertTrue("read timeout must be finite", defaults.readTimeoutMillis > 0)
        assertEquals("an established SSE stream stays long-lived", 0, defaults.callTimeoutMillis)
    }

    @Test
    fun redirectIsNotFollowed() {
        server.enqueue(
            MockResponse().setResponseCode(302)
                .addHeader("Location", server.url("/redirect-target"))
        )
        server.enqueue(sseResponse(successfulStreamFrames(SESSION_ID)))

        val listener = RecordingListener()
        client.openTurn(baseUrl(), SecretValue(FIXTURE_KEY), SESSION_ID, "Hallo", listener)
        assertTrue(listener.finished.await(10, TimeUnit.SECONDS))
        assertEquals(
            listOf<ChatTurnEvent>(ChatTurnEvent.TurnFailed(ChatStreamError.Http)),
            listener.events
        )
        assertEquals(1, server.requestCount)
    }

    @Test
    fun wrongContentTypeIsIncompatible() {
        server.enqueue(
            MockResponse()
                .addHeader("Content-Type", "application/json")
                .setBody("{}")
        )
        val listener = RecordingListener()
        client.openTurn(baseUrl(), SecretValue(FIXTURE_KEY), "plain-id", "Hallo", listener)
        assertTrue(listener.finished.await(10, TimeUnit.SECONDS))
        assertEquals(
            listOf<ChatTurnEvent>(ChatTurnEvent.TurnFailed(ChatStreamError.IncompatibleApi)),
            listener.events
        )
    }

    @Test
    fun localValidationFailuresSendNoRequest() {
        listOf(
            baseUrl() to "blank-message",
            "http://example.invalid" to "http-url",
            "not-a-url" to "bad-url",
            baseUrl() to "blank-key"
        ).forEach { (url, label) ->
            val key = if (label == "blank-key") "" else FIXTURE_KEY
            val message = if (label == "blank-message") "   " else "Hallo"
            val listener = RecordingListener()
            client.openTurn(url, SecretValue(key), "plain-id", message, listener)
            assertTrue("$label did not finish", listener.finished.await(10, TimeUnit.SECONDS))
            assertEquals(
                listOf<ChatTurnEvent>(ChatTurnEvent.TurnFailed(ChatStreamError.InvalidInput)),
                listener.events
            )
        }
        // Session-ID validation happens before any request.
        val sessionListener = RecordingListener()
        client.openTurn(baseUrl(), SecretValue(FIXTURE_KEY), "  ", "Hallo", sessionListener)
        assertTrue(sessionListener.finished.await(10, TimeUnit.SECONDS))
        assertEquals(
            listOf<ChatTurnEvent>(ChatTurnEvent.TurnFailed(ChatStreamError.InvalidInput)),
            sessionListener.events
        )
        assertEquals(0, server.requestCount)
    }

    @Test
    fun tlsFailureMapsToClosedTlsError() {
        server.enqueue(sseResponse(successfulStreamFrames(SESSION_ID)))
        val untrusted = HermesSessionStreamClient(OkHttpClient.Builder().build())
        val listener = RecordingListener()
        untrusted.openTurn(baseUrl(), SecretValue(FIXTURE_KEY), SESSION_ID, "Hallo", listener)
        assertTrue(listener.finished.await(10, TimeUnit.SECONDS))
        assertEquals(
            listOf<ChatTurnEvent>(ChatTurnEvent.TurnFailed(ChatStreamError.Tls)),
            listener.events
        )
        listener.events.forEach { event ->
            assertFalse(event.toString().contains(FIXTURE_KEY))
        }
    }

    @Test
    fun timeoutMapsToClosedTimeoutError() {
        val shortClient = trustedClient.newBuilder()
            .readTimeout(100, TimeUnit.MILLISECONDS)
            .callTimeout(300, TimeUnit.MILLISECONDS)
            .build()
        server.enqueue(
            sseResponse(successfulStreamFrames(SESSION_ID)).setBodyDelay(2000, TimeUnit.MILLISECONDS)
        )
        val listener = RecordingListener()
        HermesSessionStreamClient(shortClient)
            .openTurn(baseUrl(), SecretValue(FIXTURE_KEY), SESSION_ID, "Hallo", listener)
        assertTrue(listener.finished.await(10, TimeUnit.SECONDS))
        assertEquals(
            listOf<ChatTurnEvent>(ChatTurnEvent.TurnFailed(ChatStreamError.Timeout)),
            listener.events
        )
    }

    @Test
    fun unreachableServerMapsToClosedNetworkError() {
        val stopped = MockWebServer().apply { start() }
        val unavailable = stopped.url("/").toString().replace("http://", "https://")
        stopped.shutdown()
        val listener = RecordingListener()
        client.openTurn(unavailable, SecretValue(FIXTURE_KEY), "plain-id", "Hallo", listener)
        assertTrue(listener.finished.await(15, TimeUnit.SECONDS))
        assertTrue(
            listener.events == listOf<ChatTurnEvent>(
                ChatTurnEvent.TurnFailed(ChatStreamError.Network)
            ) || listener.events == listOf<ChatTurnEvent>(
                ChatTurnEvent.TurnFailed(ChatStreamError.Tls)
            )
        )
        assertFalse(listener.events.toString().contains(FIXTURE_KEY))
        assertFalse(listener.events.toString().contains(unavailable))
    }

    @Test
    fun oversizedSingleLineIsRejectedClosed() {
        server.enqueue(
            MockResponse()
                .addHeader("Content-Type", "text/event-stream")
                .setChunkedBody(
                    "event: run.started\ndata: " + "x".repeat(70 * 1024) + "\n\n",
                    4096
                )
        )
        val listener = RecordingListener()
        client.openTurn(baseUrl(), SecretValue(FIXTURE_KEY), "plain-id", "Hallo", listener)
        assertTrue(listener.finished.await(10, TimeUnit.SECONDS))
        assertEquals(
            listOf<ChatTurnEvent>(ChatTurnEvent.TurnFailed(ChatStreamError.IncompatibleApi)),
            listener.events
        )
    }

    @Test
    fun malformedUtf8BytesFailClosedWithoutDynamicData() {
        // Synthetic invalid UTF-8 in the stream body: a valid SSE prefix
        // followed by the raw bytes 0xFF 0x28, which are not valid UTF-8.
        // The strict decoder must fail closed with IncompatibleApi and
        // never silently substitute a replacement character.
        val body = okio.Buffer()
            .writeUtf8("event: run.started\n")
            .writeUtf8("data: {\"session_id\":\"synthetic-session\"")
            .writeByte(0xFF)
            .writeByte(0x28)
            .writeUtf8("}\n\n")
        server.enqueue(
            MockResponse()
                .addHeader("Content-Type", "text/event-stream")
                .setChunkedBody(body, 4096)
        )
        val listener = RecordingListener()
        client.openTurn(baseUrl(), SecretValue(FIXTURE_KEY), SESSION_ID, "Hallo", listener)
        assertTrue(listener.finished.await(10, TimeUnit.SECONDS))
        assertEquals(
            listOf<ChatTurnEvent>(ChatTurnEvent.TurnFailed(ChatStreamError.IncompatibleApi)),
            listener.events
        )
        // Closed error: no dynamic payload, key or URL detail leaks.
        listener.events.forEach { event ->
            assertFalse(event.toString().contains(FIXTURE_KEY))
            assertFalse(event.toString().contains("synthetic-session"))
        }
    }

    @Test
    fun serverErrorEventMapsToClosedGenericError() {
        server.enqueue(
            sseResponse(
                listOf(
                    frame("run.started", 1, JSONObject(), SESSION_ID),
                    frame("message.started", 2, messageExtra(), SESSION_ID),
                    frame("error", 3, JSONObject().put("message", "dynamic server detail"), SESSION_ID),
                    frame("done", 4, JSONObject(), SESSION_ID)
                )
            )
        )
        val listener = RecordingListener()
        client.openTurn(baseUrl(), SecretValue(FIXTURE_KEY), SESSION_ID, "Hallo", listener)
        assertTrue(listener.finished.await(10, TimeUnit.SECONDS))
        assertEquals(
            listOf<ChatTurnEvent>(
                ChatTurnEvent.RunStarted(RUN_ID),
                ChatTurnEvent.AssistantStarted,
                ChatTurnEvent.TurnFailed(ChatStreamError.Http)
            ),
            listener.events
        )
        assertFalse(listener.events.toString().contains("dynamic server detail"))
    }

    @Test
    fun successDeliversNonTerminalEventsLiveAndBuffersTerminalUntilValidatedDone() {
        // Keepalive comments between `run.completed` and `done` model the
        // server's idle pause; throttling keeps `done` off the wire while
        // the test inspects the listener. Non-terminal events
        // (RunStarted/AssistantStarted/AssistantDelta/AssistantCompleted)
        // must reach the listener live as they are parsed; only the
        // terminal event (TurnCompleted/TurnFailed) is buffered until a
        // valid `done` has been seen.
        val frames = successfulStreamFrames(SESSION_ID).toMutableList()
        frames.add(5, ": keepalive\n\n".repeat(150))
        server.enqueue(
            sseResponse(frames).throttleBody(512, 150, TimeUnit.MILLISECONDS)
        )
        val listener = RecordingListener()
        client.openTurn(baseUrl(), SecretValue(FIXTURE_KEY), SESSION_ID, "Hallo", listener)
        // Wait deterministically for the first live non-terminal event instead
        // of relying on a fixed wall-clock sleep: as soon as one non-terminal
        // event has been delivered, the stream is demonstrably mid-flight while
        // the throttled `done` is still on the wire. The terminal-buffering
        // assertions below hold regardless (a bounded deadline keeps this
        // robust on a slow runner).
        val liveDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < liveDeadline) {
            val sawNonTerminal = synchronized(listener.events) {
                listener.events.any {
                    it !is ChatTurnEvent.TurnCompleted && it !is ChatTurnEvent.TurnFailed
                }
            }
            if (sawNonTerminal) break
            Thread.sleep(10)
        }
        assertFalse("finished before `done` was written", listener.finished.await(0, TimeUnit.MILLISECONDS))
        // Snapshot the events under the same lock the listener uses; do
        // NOT assert which non-terminal events arrived by now (that
        // depends on throttle chunking), and do NOT require the list to
        // be empty.
        val eventsBeforeDone = synchronized(listener.events) { listener.events.toList() }
        assertFalse(
            "TurnCompleted released before `done`",
            eventsBeforeDone.contains(ChatTurnEvent.TurnCompleted())
        )
        assertTrue(
            "TurnFailed released before `done`",
            eventsBeforeDone.none { it is ChatTurnEvent.TurnFailed }
        )
        assertTrue("stream did not finish", listener.finished.await(10, TimeUnit.SECONDS))
        assertEquals(
            listOf<ChatTurnEvent>(
                ChatTurnEvent.RunStarted(RUN_ID),
                ChatTurnEvent.AssistantStarted,
                ChatTurnEvent.AssistantDelta("Hallo"),
                ChatTurnEvent.AssistantCompleted("Hallo Welt"),
                ChatTurnEvent.TurnCompleted()
            ),
            listener.events
        )
        assertEquals(1, listener.events.count { it is ChatTurnEvent.TurnCompleted })
    }

    @Test
    fun validDoneTerminatesImmediatelyWithoutWaitingForEofOrReadTimeout() {
        // Deterministic immediate-termination check: the complete stream
        // up to and including the final `done` frame is delivered in
        // the first throttle period; at least one further byte (the
        // first byte of a truncated next frame, deliberately NOT a
        // valid frame) follows, held back for a much longer period.
        // The client must close the response and finish as soon as
        // `done` is processed - long before the delayed byte arrives
        // and far below any read timeout. A client that kept reading
        // would block on the delayed byte and could not finish.
        //
        // Throttle construction: MockWebServer throttles the chunked
        // framing (hex chunk sizes, CRLF line breaks, final 0-chunk)
        // in addition to the payload bytes. The period-1 budget must
        // therefore be sized as `done` payload bytes plus a generous
        // framing margin, so that every payload byte up to and
        // including the `done` frame is written in the first period -
        // regardless of how many chunk frames the transport splits
        // them into - while the single trailing byte exceeds the
        // budget and stays on the wire until the 60 s period.
        val doneFrames = successfulStreamFrames(SESSION_ID).joinToString("")
        val trailingByte = ':' // first byte of a truncated next frame
        val chunkSize = doneFrames.length
        val firstPeriodBudget = (doneFrames.length + 1024L)
        server.enqueue(
            sseResponse(emptyList())
                .setChunkedBody(doneFrames + trailingByte, chunkSize)
                .throttleBody(firstPeriodBudget, 60, TimeUnit.SECONDS)
        )
        val listener = RecordingListener()
        client.openTurn(baseUrl(), SecretValue(FIXTURE_KEY), SESSION_ID, "Hallo", listener)
        val started = System.nanoTime()
        assertTrue("stream did not finish before the delayed byte",
            listener.finished.await(10, TimeUnit.SECONDS))
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000L
        assertTrue(
            "finished after ${elapsedMillis} ms: the turn must end as soon as `done` " +
                "is processed, without waiting for the delayed byte, EOF or a read timeout",
            elapsedMillis < 5000
        )
        assertEquals(
            listOf<ChatTurnEvent>(
                ChatTurnEvent.RunStarted(RUN_ID),
                ChatTurnEvent.AssistantStarted,
                ChatTurnEvent.AssistantDelta("Hallo"),
                ChatTurnEvent.AssistantCompleted("Hallo Welt"),
                ChatTurnEvent.TurnCompleted()
            ),
            listener.events
        )
        assertEquals(1, listener.events.count { it is ChatTurnEvent.TurnCompleted })
        assertEquals(1, listener.finishedCalls)
        // The delayed second chunk is never read: only the first
        // chunk is delivered by the mock and the request is done.
        val request = checkNotNull(server.takeRequest(10, TimeUnit.SECONDS)) {
            "expected one recorded request"
        }
        assertEquals("POST", request.method)
        request.body.close()
    }

    @Test
    fun completeUnknownFrameAfterDoneInSameReadIsIgnoredAndTurnEndsSuccessfully() {
        // Single unthrottled body: the complete success stream up to
        // and including `done`, followed immediately by a complete but
        // unknown frame. Everything is on the wire before the client
        // starts reading, so a correct implementation must process
        // `done`, stop consuming the chunk and finish successfully -
        // the post-`done` frame must never reach the pipeline.
        val body = successfulStreamFrames(SESSION_ID).joinToString("") +
            frame("unknown.event", 7, JSONObject(), SESSION_ID)
        server.enqueue(
            sseResponse(emptyList())
                .setChunkedBody(body, 4096)
        )
        val listener = RecordingListener()
        client.openTurn(baseUrl(), SecretValue(FIXTURE_KEY), SESSION_ID, "Hallo", listener)
        assertTrue("stream did not finish", listener.finished.await(10, TimeUnit.SECONDS))
        assertEquals(
            listOf<ChatTurnEvent>(
                ChatTurnEvent.RunStarted(RUN_ID),
                ChatTurnEvent.AssistantStarted,
                ChatTurnEvent.AssistantDelta("Hallo"),
                ChatTurnEvent.AssistantCompleted("Hallo Welt"),
                ChatTurnEvent.TurnCompleted()
            ),
            listener.events
        )
        assertEquals(1, listener.events.count { it is ChatTurnEvent.TurnCompleted })
        assertEquals(1, listener.finishedCalls)
        listener.events.forEach { event ->
            assertFalse("post-`done` frame leaked into events", event.toString().contains("unknown.event"))
        }
    }

    @Test
    fun partialLineAfterDoneInSameReadIsIgnoredAndTurnEndsSuccessfully() {
        // Single unthrottled body: the complete success stream up to
        // and including `done`, followed immediately by a truncated
        // next frame - one partial line that never reaches its empty
        // frame boundary. A correct implementation must end the turn
        // successfully as soon as `done` is processed; the partial
        // line after `done` must not turn the valid turn into
        // IncompatibleApi.
        val body = successfulStreamFrames(SESSION_ID).joinToString("") +
            "event: unknown.event\ndata: {\"session_id\":\"synthetic-session\""
        server.enqueue(
            sseResponse(emptyList())
                .setChunkedBody(body, 4096)
        )
        val listener = RecordingListener()
        client.openTurn(baseUrl(), SecretValue(FIXTURE_KEY), SESSION_ID, "Hallo", listener)
        assertTrue("stream did not finish", listener.finished.await(10, TimeUnit.SECONDS))
        assertEquals(
            listOf<ChatTurnEvent>(
                ChatTurnEvent.RunStarted(RUN_ID),
                ChatTurnEvent.AssistantStarted,
                ChatTurnEvent.AssistantDelta("Hallo"),
                ChatTurnEvent.AssistantCompleted("Hallo Welt"),
                ChatTurnEvent.TurnCompleted()
            ),
            listener.events
        )
        assertEquals(1, listener.events.count { it is ChatTurnEvent.TurnCompleted })
        assertEquals(1, listener.finishedCalls)
    }

    @Test
    fun burstOfMoreThanChannelBufferEventsLosesNothingAndPreservesOrder() {
        // Burst/backpressure proof (Mission 006A acceptance): the
        // repository forwards transport events into the callbackFlow
        // channel (default capacity 64) with `trySendBlocking`. This
        // test drives the real `StreamingChatRepository` + real
        // `HermesSessionStreamClient` against MockWebServer with
        // [BURST_DELTA_COUNT] = 200 `assistant.delta` events — more
        // than three full default-channel buffers — and proves that
        // not a single event is silently dropped: every delta reaches
        // the collector exactly once, in server order, with a
        // `TurnCompleted` as the final event and no failure event.
        val deltaCount = BURST_DELTA_COUNT
        val deltas = (1..deltaCount).map { index -> "chunk-$index" }
        val body = buildString {
            append(frame("run.started", 1, JSONObject(), SESSION_ID))
            append(frame("message.started", 2, messageExtra(), SESSION_ID))
            deltas.forEachIndexed { position, delta ->
                append(
                    frame("assistant.delta", 3L + position, deltaExtra(delta), SESSION_ID)
                )
            }
            append(
                frame(
                    "assistant.completed",
                    3L + deltaCount,
                    JSONObject()
                        .put("message_id", "msg_synthetic")
                        .put("content", "burst-final")
                        .put("completed", true)
                        .put("partial", false)
                        .put("interrupted", false),
                    SESSION_ID
                )
            )
            append(frame("run.completed", 4L + deltaCount, JSONObject(), SESSION_ID))
            append(frame("done", 5L + deltaCount, JSONObject(), SESSION_ID))
        }
        // One 16 KiB chunk at a time: the body is large enough that
        // dozens of frames arrive per read, so the repository's
        // transport->channel path is continuously under burst load.
        server.enqueue(
            sseResponse(emptyList())
                .setChunkedBody(body, 16 * 1024)
        )

        // The profile base URL is the real MockWebServer address with a
        // path prefix: the client must preserve the prefix and reach the
        // synthetic SSE stream.
        val burstProfile = ServerProfile.create(
            BURST_PROFILE_ID,
            "Burst",
            server.url("/burst").toString().trimEnd('/')
        )
        val repository = StreamingChatRepository(
            profileRepository = ServerProfileRepository(
                FakeMetadataStore(mutableListOf(burstProfile)),
                FakeCredentialStore(
                    mutableMapOf(BURST_PROFILE_ID to SecretValue(FIXTURE_KEY))
                )
            ),
            credentialStore = FakeCredentialStore(
                mutableMapOf(BURST_PROFILE_ID to SecretValue(FIXTURE_KEY))
            ),
            streamTransport = client
        )

        val collected = java.util.Collections.synchronizedList(
            mutableListOf<ChatTurnEvent>()
        )
        val finished = CountDownLatch(1)
        val collector = Thread {
            kotlinx.coroutines.runBlocking {
                repository.streamTurn(BURST_PROFILE_ID, SESSION_ID, "Hallo")
                    .collect { event ->
                        collected += event
                    }
            }
            finished.countDown()
        }
        collector.start()
        assertTrue(
            "burst stream of $deltaCount deltas did not finish: an event was lost or the send blocked forever",
            finished.await(30, TimeUnit.SECONDS)
        )

        // Exactly the closed event set, in exact server order: no
        // missing, duplicated or reordered event anywhere in the
        // 200-delta burst.
        val expected = mutableListOf<ChatTurnEvent>(
            ChatTurnEvent.RunStarted(RUN_ID),
            ChatTurnEvent.AssistantStarted
        )
        expected += deltas.map { delta -> ChatTurnEvent.AssistantDelta(delta) }
        expected += listOf(
            ChatTurnEvent.AssistantCompleted("burst-final"),
            ChatTurnEvent.TurnCompleted()
        )
        assertEquals(
            "burst lost, reordered or duplicated events",
            expected,
            collected.toList()
        )
        assertEquals(1, collected.count { it is ChatTurnEvent.TurnCompleted })
        assertTrue(
            "a failure event appeared in a valid burst turn",
            collected.none { it is ChatTurnEvent.TurnFailed }
        )
        collector.join(5_000)
    }

    @Test
    fun runCompletedWithoutDoneIsIncompatibleNeverTurnCompleted() {
        server.enqueue(
            sseResponse(
                listOf(
                    frame("run.started", 1, JSONObject(), SESSION_ID),
                    frame("message.started", 2, messageExtra(), SESSION_ID),
                    frame("assistant.completed", 3, completedExtra(), SESSION_ID),
                    frame("run.completed", 4, JSONObject(), SESSION_ID)
                )
            )
        )
        val listener = RecordingListener()
        client.openTurn(baseUrl(), SecretValue(FIXTURE_KEY), SESSION_ID, "Hallo", listener)
        assertTrue(listener.finished.await(10, TimeUnit.SECONDS))
        assertEquals(
            listOf<ChatTurnEvent>(
                ChatTurnEvent.RunStarted(RUN_ID),
                ChatTurnEvent.AssistantStarted,
                ChatTurnEvent.AssistantCompleted("Hallo Welt"),
                ChatTurnEvent.TurnFailed(ChatStreamError.IncompatibleApi)
            ),
            listener.events
        )
        assertFalse(listener.events.contains(ChatTurnEvent.TurnCompleted()))
    }

    @Test
    fun errorWithoutDoneIsIncompatibleAndDynamicMessageNeverVisible() {
        server.enqueue(
            sseResponse(
                listOf(
                    frame("run.started", 1, JSONObject(), SESSION_ID),
                    frame("message.started", 2, messageExtra(), SESSION_ID),
                    frame("error", 3, JSONObject().put("message", "dynamic server detail"), SESSION_ID)
                )
            )
        )
        val listener = RecordingListener()
        client.openTurn(baseUrl(), SecretValue(FIXTURE_KEY), SESSION_ID, "Hallo", listener)
        assertTrue(listener.finished.await(10, TimeUnit.SECONDS))
        assertEquals(
            listOf<ChatTurnEvent>(
                ChatTurnEvent.RunStarted(RUN_ID),
                ChatTurnEvent.AssistantStarted,
                ChatTurnEvent.TurnFailed(ChatStreamError.IncompatibleApi)
            ),
            listener.events
        )
        assertFalse(listener.events.toString().contains("dynamic server detail"))
    }

    @Test
    fun doneWithoutTerminalIsIncompatible() {
        server.enqueue(
            sseResponse(
                listOf(
                    frame("run.started", 1, JSONObject(), SESSION_ID),
                    frame("done", 2, JSONObject(), SESSION_ID)
                )
            )
        )
        val listener = RecordingListener()
        client.openTurn(baseUrl(), SecretValue(FIXTURE_KEY), SESSION_ID, "Hallo", listener)
        assertTrue(listener.finished.await(10, TimeUnit.SECONDS))
        assertEquals(
            listOf<ChatTurnEvent>(
                ChatTurnEvent.RunStarted(RUN_ID),
                ChatTurnEvent.TurnFailed(ChatStreamError.IncompatibleApi)
            ),
            listener.events
        )
    }

    @Test
    fun productionDefaultReadTimeoutCoversConfirmedKeepaliveContract() {
        // Confirmed against the installed Hermes server
        // (gateway/platforms/api_server.py):
        // CHAT_COMPLETIONS_SSE_KEEPALIVE_SECONDS = 30.0. A read timeout at
        // or below the keepalive interval aborts a normal idle turn.
        assertTrue(
            "read timeout must exceed the 30s server keepalive interval",
            HermesSessionStreamClient.STREAM_READ_TIMEOUT_SECONDS >
                HermesSessionStreamClient.SERVER_SSE_KEEPALIVE_SECONDS
        )
        assertTrue(
            "read timeout must stay within a sane upper bound",
            HermesSessionStreamClient.STREAM_READ_TIMEOUT_SECONDS <= MAX_REASONABLE_READ_TIMEOUT_SECONDS
        )
        val defaults = HermesSessionStreamClient.newDefaultStreamClient()
        // readTimeoutMillis is Int; convert the Long constant explicitly
        // so JUnit compares (Int, Int), not (Long, Int).
        assertEquals(
            (HermesSessionStreamClient.STREAM_READ_TIMEOUT_SECONDS * 1000).toInt(),
            defaults.readTimeoutMillis
        )
        assertEquals(0, defaults.callTimeoutMillis)
    }

    @Test
    fun eventSessionMismatchIsRejectedAsIncompatible() {
        server.enqueue(
            sseResponse(
                listOf(
                    frame("run.started", 1, JSONObject(), "other-session")
                )
            )
        )
        val listener = RecordingListener()
        client.openTurn(baseUrl(), SecretValue(FIXTURE_KEY), SESSION_ID, "Hallo", listener)
        assertTrue(listener.finished.await(10, TimeUnit.SECONDS))
        assertEquals(
            listOf<ChatTurnEvent>(ChatTurnEvent.TurnFailed(ChatStreamError.IncompatibleApi)),
            listener.events
        )
    }

    @Test
    fun keepaliveCommentsBetweenFramesAreIgnored() {
        val frames = successfulStreamFrames(SESSION_ID).toMutableList()
        frames.add(1, ": keepalive\n\n")
        server.enqueue(sseResponse(frames))
        val listener = RecordingListener()
        client.openTurn(baseUrl(), SecretValue(FIXTURE_KEY), SESSION_ID, "Hallo", listener)
        assertTrue(listener.finished.await(10, TimeUnit.SECONDS))
        assertEquals(
            listOf<ChatTurnEvent>(
                ChatTurnEvent.RunStarted(RUN_ID),
                ChatTurnEvent.AssistantStarted,
                ChatTurnEvent.AssistantDelta("Hallo"),
                ChatTurnEvent.AssistantCompleted("Hallo Welt"),
                ChatTurnEvent.TurnCompleted()
            ),
            listener.events
        )
    }

    @Test
    fun finalDeliveryStillSignalsFinishedWhenListenerThrows() {
        server.enqueue(sseResponse(successfulStreamFrames(SESSION_ID)))
        val listener = ThrowingEventListener { it is ChatTurnEvent.TurnCompleted }

        client.openTurn(baseUrl(), SecretValue(FIXTURE_KEY), SESSION_ID, "Hallo", listener)

        assertTrue(
            "onFinished must follow a throwing final onEvent",
            listener.finished.await(5, TimeUnit.SECONDS)
        )
        assertEquals(1, listener.finishedCalls)
    }

    @Test
    fun exceptionDeliveryStillSignalsFinishedWhenListenerThrows() {
        server.enqueue(sseResponse(listOf(frame("unknown.event", 1))))
        val listener = ThrowingEventListener { it is ChatTurnEvent.TurnFailed }

        client.openTurn(baseUrl(), SecretValue(FIXTURE_KEY), SESSION_ID, "Hallo", listener)

        assertTrue(
            "onFinished must follow a throwing exception-path onEvent",
            listener.finished.await(5, TimeUnit.SECONDS)
        )
        assertEquals(1, listener.finishedCalls)
    }

    @Test
    fun immediateFailureStillSignalsFinishedWhenListenerThrows() {
        val listener = ThrowingEventListener { true }

        assertThrows(IllegalStateException::class.java) {
            client.openTurn(
                "http://example.invalid",
                SecretValue(FIXTURE_KEY),
                SESSION_ID,
                "Hallo",
                listener
            )
        }

        assertEquals(1, listener.finishedCalls)
    }

    @Test
    fun closeCancelsTheCallAndSuppressesFurtherEvents() {
        server.enqueue(
            sseResponse(successfulStreamFrames(SESSION_ID))
                .setBodyDelay(5, TimeUnit.SECONDS)
        )
        val listener = RecordingListener()
        val handle = client.openTurn(baseUrl(), SecretValue(FIXTURE_KEY), SESSION_ID, "Hallo", listener)
        handle.close()
        assertTrue(listener.finished.await(10, TimeUnit.SECONDS))
        assertTrue(listener.events.isEmpty())
        // Transport contract: the completion signal fires exactly once,
        // even though both the close path and the canceled reader thread
        // race to finish the listener.
        assertEquals(1, listener.finishedCalls)
    }

    private fun baseUrl(): String = server.url("/").toString().trimEnd('/')

    /** Confirmed re-attach state of an interrupted attempt (P4). */
    private fun resumeState(lastSeq: Long): ChatStreamResumeState = ChatStreamResumeState(
        runId = RUN_ID,
        lastSeq = lastSeq,
        messageStarted = true,
        messageId = "msg_synthetic",
        composed = "Hallo",
        assistantCompleted = false,
        finalText = null,
        runCompleted = false,
        sawError = false
    )

    private fun sseResponse(frames: List<String>): MockResponse = MockResponse()
        .addHeader("Content-Type", "text/event-stream")
        .setChunkedBody(frames.joinToString(""), 4096)

    private fun successfulStreamFrames(sessionId: String = SESSION_ID): List<String> = listOf(
        frame("run.started", 1, JSONObject(), sessionId),
        frame("message.started", 2, messageExtra(), sessionId),
        frame("assistant.delta", 3, deltaExtra("Hallo"), sessionId),
        frame(
            "assistant.completed",
            4,
            JSONObject()
                .put("message_id", "msg_synthetic")
                .put("content", "Hallo Welt")
                .put("completed", true)
                .put("partial", false)
                .put("interrupted", false),
            sessionId
        ),
        frame("run.completed", 5, JSONObject(), sessionId),
        frame("done", 6, JSONObject(), sessionId)
    )

    private fun frame(
        name: String,
        seq: Long,
        extra: JSONObject = JSONObject(),
        sessionId: String = SESSION_ID
    ): String {
        val payload = JSONObject()
            .put("session_id", sessionId)
            .put("run_id", RUN_ID)
            .put("seq", seq)
            .put("ts", 1000.0)
        for (key in extra.keys()) {
            payload.put(key, extra.get(key))
        }
        return "event: $name\ndata: " + payload.toString() + "\n\n"
    }

    private fun messageExtra(): JSONObject = JSONObject()
        .put("message", JSONObject().put("id", "msg_synthetic").put("role", "assistant"))

    private fun deltaExtra(delta: String): JSONObject = JSONObject()
        .put("message_id", "msg_synthetic")
        .put("delta", delta)

    private fun completedExtra(): JSONObject = JSONObject()
        .put("message_id", "msg_synthetic")
        .put("content", "Hallo Welt")
        .put("completed", true)
        .put("partial", false)
        .put("interrupted", false)

    @Test
    fun limitingInputStreamDeliversAtMostMaxBytesViaRead() {
        // Small limit: 4 bytes of 6-byte source, single-byte reads.
        val source = java.util.Arrays.asList('a', 'b', 'c', 'd', 'e', 'f')
            .joinToString("").toByteArray().inputStream()
        val limited = HermesSessionStreamClient.LimitingInputStream(source, 4L)
        assertEquals('a'.code, limited.read())
        assertEquals('b'.code, limited.read())
        assertEquals('c'.code, limited.read())
        assertEquals('d'.code, limited.read())
        // Exactly at the limit with bytes remaining: the next read
        // probes one recognition byte and fails closed.
        assertThrows(StreamLimitException::class.java) { limited.read() }
        // After a limit violation every further read fails closed.
        assertThrows(StreamLimitException::class.java) { limited.read() }
    }

    @Test
    fun limitingInputStreamAtExactLimitIsRegularEof() {
        // Source is exactly maxBytes long: the exhausted budget ends
        // with a regular -1 EOF, no exception.
        val source = "abcd".toByteArray().inputStream()
        val limited = HermesSessionStreamClient.LimitingInputStream(source, 4L)
        assertEquals('a'.code, limited.read())
        assertEquals('b'.code, limited.read())
        assertEquals('c'.code, limited.read())
        assertEquals('d'.code, limited.read())
        assertEquals(-1, limited.read())
        assertEquals(-1, limited.read())
    }

    @Test
    fun limitingInputStreamDeliversAtMostMaxBytesViaBulkRead() {
        // 4-byte budget, 6-byte source, one large requested buffer:
        // at most the budget is delivered, the probe read fails
        // closed.
        val source = "abcdef".toByteArray().inputStream()
        val limited = HermesSessionStreamClient.LimitingInputStream(source, 4L)
        val buffer = ByteArray(64)
        assertEquals(4, limited.read(buffer, 0, buffer.size))
        // Only the delivered range is defined; the rest of the buffer
        // is untouched.
        assertEquals("abcd", buffer.copyOfRange(0, 4).toString(Charsets.US_ASCII))
        assertThrows(StreamLimitException::class.java) {
            limited.read(buffer, 0, buffer.size)
        }
    }

    @Test
    fun limitingInputStreamBulkReadExactlyAtLimitIsRegularEof() {
        // Source is exactly maxBytes long and the requested buffer is
        // larger: the first read delivers exactly the budget, the next
        // read is a regular -1 EOF (the probe finds no further byte).
        val source = "abcd".toByteArray().inputStream()
        val limited = HermesSessionStreamClient.LimitingInputStream(source, 4L)
        val buffer = ByteArray(1024)
        assertEquals(4, limited.read(buffer, 0, buffer.size))
        assertEquals("abcd", buffer.copyOfRange(0, 4).toString(Charsets.US_ASCII))
        assertEquals(-1, limited.read(buffer, 0, buffer.size))
        assertEquals(-1, limited.read(buffer, 0, buffer.size))
    }

    @Test
    fun limitingInputStreamShortBulkReadsStayWithinBudget() {
        // Mixed short bulk reads and single-byte reads (as a real
        // socket may deliver them): the budget is tracked across both
        // read flavors and the limit violation is raised on the very
        // next read after the budget is exhausted.
        val source = ShortReadStream("abcdef".toByteArray(), 2, 1)
        val limited = HermesSessionStreamClient.LimitingInputStream(source, 4L)
        val buffer = ByteArray(8)
        assertEquals(2, limited.read(buffer, 0, buffer.size))
        assertEquals("ab", buffer.copyOfRange(0, 2).toString(Charsets.US_ASCII))
        assertEquals('c'.code, limited.read())
        assertEquals(1, limited.read(buffer, 0, buffer.size))
        assertEquals('d'.code.toByte(), buffer[0])
        assertThrows(StreamLimitException::class.java) {
            limited.read(buffer, 0, buffer.size)
        }
        assertThrows(StreamLimitException::class.java) { limited.read() }
    }

    @Test
    fun streamLimitExceptionCarriesNoDynamicData() {
        // Closed exception: no message, no cause - no dynamic data can
        // escape into an event, result or log.
        val exception = StreamLimitException()
        assertNull(exception.message)
        assertNull(exception.cause)
    }

    private class ThrowingEventListener(
        private val shouldThrow: (ChatTurnEvent) -> Boolean
    ) : ChatStreamListener {
        val finished = CountDownLatch(1)
        var finishedCalls = 0
            private set

        override fun onEvent(event: ChatTurnEvent) {
            if (shouldThrow(event)) {
                throw IllegalStateException("synthetic listener failure")
            }
        }

        override fun onFinished() {
            finishedCalls += 1
            finished.countDown()
        }
    }

    private class RecordingListener : ChatStreamListener {
        val events = mutableListOf<ChatTurnEvent>()
        val finished = CountDownLatch(1)
        // `onFinished` must be called exactly once; the latch alone
        // cannot count a second call. Visible after `finished.await`.
        var finishedCalls = 0
            private set

        override fun onEvent(event: ChatTurnEvent) {
            synchronized(events) {
                events += event
            }
        }

        override fun onFinished() {
            finishedCalls += 1
            finished.countDown()
        }
    }

    /**
     * In-memory source that delivers at most [firstBulkMax] bytes to
     * the first bulk read and at most [singleByteMax] bytes to each
     * later bulk read, so a test can exercise the short-read behavior
     * a real socket exhibits. Single-byte [read]s always deliver one
     * byte.
     */
    private class ShortReadStream(
        private val data: ByteArray,
        private val firstBulkMax: Int,
        private val singleByteMax: Int
    ) : InputStream() {
        private var position = 0
        private var bulkReadsDone = 0

        override fun read(): Int {
            if (position >= data.size) return -1
            val value = data[position].toInt() and 0xFF
            position += 1
            return value
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (position >= data.size) return -1
            val remaining = data.size - position
            val budget = if (bulkReadsDone == 0) firstBulkMax else singleByteMax
            bulkReadsDone += 1
            val count = minOf(budget, len, remaining)
            data.copyInto(b, off, position, position + count)
            position += count
            return count
        }
    }

    private class FakeMetadataStore(
        private val profiles: MutableList<ServerProfile>
    ) : ServerProfileMetadataStore {
        override fun save(profile: ServerProfile) {
            profiles.removeAll { it.id == profile.id }
            profiles += profile
        }

        override fun get(profileId: String): ServerProfile? =
            profiles.firstOrNull { it.id == profileId }

        override fun list(): List<ServerProfile> = profiles.sortedBy { it.id }

        override fun delete(profileId: String) {
            profiles.removeAll { it.id == profileId }
        }
    }

    private class FakeCredentialStore(
        private val values: MutableMap<String, SecretValue>
    ) : CredentialStore {
        override fun put(profileId: String, secret: SecretValue) {
            values[profileId] = secret
        }

        override fun get(profileId: String): SecretValue? = values[profileId]

        override fun delete(profileId: String) {
            values.remove(profileId)
        }
    }

    private companion object {
        const val FIXTURE_KEY = "fixture-credential"
        const val SESSION_ID = "synthetic-session"
        const val RUN_ID = "run_synthetic"
        const val IMAGE_DATA_URL = "data:image/png;base64,AA=="
        // Display name of the synthetic attachment; it must never leak
        // into a wire body, which carries only the data URL.
        const val ATTACHMENT_FILE_NAME = "foto.png"
        const val ENCODED_SESSION_ID = "synthetic/sess ion"
        const val MAX_REASONABLE_READ_TIMEOUT_SECONDS = 120L
        const val BURST_PROFILE_ID = "burst-profile-id"
        // Well above the callbackFlow default channel capacity of 64.
        const val BURST_DELTA_COUNT = 200
    }
}
