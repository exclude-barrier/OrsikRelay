package io.github.excludebarrier.orsikrelay.network

import io.github.excludebarrier.orsikrelay.domain.chat.ChatStreamError
import io.github.excludebarrier.orsikrelay.domain.chat.ChatStreamHandle
import io.github.excludebarrier.orsikrelay.domain.chat.ChatStreamListener
import io.github.excludebarrier.orsikrelay.domain.chat.ChatTurnEvent
import io.github.excludebarrier.orsikrelay.domain.profile.SecretValue
import io.github.excludebarrier.orsikrelay.domain.run.ApprovalRequest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Protocol
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
 * Adversarial wire-level streams for [HermesSessionStreamClient]: alternate
 * line endings, one-byte chunks, keepalives, BOM, wrong media types,
 * truncated framing, oversized lines, duplicate frames and the interactive
 * approval opt-in.
 */
class HermesSessionStreamClientEdgeCaseTest {

    private lateinit var server: MockWebServer
    private lateinit var client: HermesSessionStreamClient

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
        val http = OkHttpClient.Builder()
            .sslSocketFactory(
                clientCertificates.sslSocketFactory(),
                clientCertificates.trustManager
            )
            .protocols(listOf(Protocol.HTTP_1_1))
            .build()
        client = HermesSessionStreamClient(http)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun crlfStreamWithKeepalivesSplitIntoOneByteChunksSucceeds() {
        val body = buildString {
            append(": keepalive\r\n\r\n")
            successFrames("\r\n").forEach { append(it) }
        }
        server.enqueue(sse(body, chunkSize = 1))

        val listener = runTurn()

        assertEquals(successEvents(), listener.events)
    }

    @Test
    fun loneCrLineEndingsAreAcceptedEndToEnd() {
        val body = successFrames("\r").joinToString("")
        server.enqueue(sse(body, chunkSize = 3))

        val listener = runTurn()

        assertEquals(successEvents(), listener.events)
    }

    @Test
    fun everyFrameBoundaryMayBeSplitByTheNetwork() {
        val body = successFrames("\n").joinToString("")
        server.enqueue(sse(body, chunkSize = 1))

        val listener = runTurn()

        assertEquals(successEvents(), listener.events)
    }

    @Test
    fun leadingBomAtStreamStartIsIgnored() {
        // WHATWG Server-sent events: "One leading U+FEFF BYTE ORDER MARK
        // character must be ignored if any are present."
        // SUSPECTED BUG: the strict parser keeps the BOM as part of the
        // first field name, so a spec-compliant stream that starts with a
        // BOM fails as IncompatibleApi.
        server.enqueue(sse("\uFEFF" + successFrames("\n").joinToString("")))

        val listener = runTurn()

        assertEquals(successEvents(), listener.events)
    }

    @Test
    fun sseContentTypeWithParametersAndMixedCaseIsAccepted() {
        server.enqueue(
            MockResponse()
                .addHeader("Content-Type", "Text/Event-Stream; charset=UTF-8")
                .setChunkedBody(successFrames("\n").joinToString(""), 4096)
        )

        val listener = runTurn()

        assertEquals(successEvents(), listener.events)
    }

    @Test
    fun missingOrLookalikeContentTypeIsClosedIncompatible() {
        val responses = listOf(
            MockResponse().setBody(successFrames("\n").joinToString("")),
            MockResponse()
                .addHeader("Content-Type", "text/event-streamish")
                .setBody(successFrames("\n").joinToString("")),
            MockResponse()
                .addHeader("Content-Type", "application/json")
                .setBody(successFrames("\n").joinToString(""))
        )

        responses.forEach { response ->
            server.enqueue(response)
            val listener = runTurn()
            assertEquals(
                listOf<ChatTurnEvent>(ChatTurnEvent.TurnFailed(ChatStreamError.IncompatibleApi)),
                listener.events
            )
        }
        assertEquals(3, server.requestCount)
    }

    @Test
    fun emptyAndKeepaliveOnlyBodiesAreTransientNetworkEnds() {
        val bodies = listOf(
            "",
            ": keepalive\n\n: keepalive\n\n"
        )

        bodies.forEach { body ->
            server.enqueue(sse(body))
            val listener = runTurn()
            assertEquals(
                listOf<ChatTurnEvent>(ChatTurnEvent.TurnFailed(ChatStreamError.Network)),
                listener.events
            )
        }
    }

    @Test
    fun aDataLineWithoutAnEventNameIsIncompatible() {
        server.enqueue(sse("data: {\"session_id\":\"$SESSION_ID\"}\n\n"))

        val listener = runTurn()

        assertEquals(
            listOf<ChatTurnEvent>(ChatTurnEvent.TurnFailed(ChatStreamError.IncompatibleApi)),
            listener.events
        )
    }

    @Test
    fun unknownEventTypeIsIncompatibleAfterTheConfirmedPrefix() {
        server.enqueue(
            sse(
                frame("run.started", 1) +
                    frame("mystery.event", 2)
            )
        )

        val listener = runTurn()

        assertEquals(
            listOf<ChatTurnEvent>(
                ChatTurnEvent.RunStarted(RUN_ID),
                ChatTurnEvent.TurnFailed(ChatStreamError.IncompatibleApi)
            ),
            listener.events
        )
    }

    @Test
    fun malformedJsonDataIsIncompatibleAfterTheConfirmedPrefix() {
        server.enqueue(
            sse(
                frame("run.started", 1) +
                    "event: assistant.delta\ndata: not-json\n\n"
            )
        )

        val listener = runTurn()

        assertEquals(
            listOf<ChatTurnEvent>(
                ChatTurnEvent.RunStarted(RUN_ID),
                ChatTurnEvent.TurnFailed(ChatStreamError.IncompatibleApi)
            ),
            listener.events
        )
    }

    @Test
    fun aDecreasingSequenceNumberIsIncompatible() {
        server.enqueue(
            sse(
                frame("run.started", 1) +
                    frame("message.started", 2, messageExtra()) +
                    frame("assistant.delta", 1, deltaExtra("late"))
            )
        )

        val listener = runTurn()

        assertEquals(
            listOf<ChatTurnEvent>(
                ChatTurnEvent.RunStarted(RUN_ID),
                ChatTurnEvent.AssistantStarted,
                ChatTurnEvent.TurnFailed(ChatStreamError.IncompatibleApi)
            ),
            listener.events
        )
    }

    @Test
    fun anIdenticalReplayedFrameIsDroppedNotDeliveredTwice() {
        val messageStarted = frame("message.started", 2, messageExtra())
        server.enqueue(
            sse(
                frame("run.started", 1) +
                    messageStarted +
                    messageStarted +
                    frame("assistant.delta", 3, deltaExtra("Hallo")) +
                    frame("assistant.completed", 4, completedExtra("Hallo Welt")) +
                    frame("run.completed", 5) +
                    frame("done", 6)
            )
        )

        val listener = runTurn()

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
    fun anUnknownSseFieldIsIncompatible() {
        server.enqueue(
            sse(
                "id: 42\n\n" +
                    frame("run.started", 1)
            )
        )

        val listener = runTurn()

        assertEquals(
            listOf<ChatTurnEvent>(ChatTurnEvent.TurnFailed(ChatStreamError.IncompatibleApi)),
            listener.events
        )
    }

    @Test
    fun aTruncatedTrailingFrameAtEofIsIncompatible() {
        server.enqueue(
            sse(
                frame("run.started", 1) +
                    "event: assistant.delta\ndata: {\"session_id\":\"$SESSION_ID\""
            )
        )

        val listener = runTurn()

        assertEquals(
            listOf<ChatTurnEvent>(
                ChatTurnEvent.RunStarted(RUN_ID),
                ChatTurnEvent.TurnFailed(ChatStreamError.IncompatibleApi)
            ),
            listener.events
        )
    }

    @Test
    fun aLineAboveTheOneMebibyteBoundIsIncompatible() {
        server.enqueue(
            sse(
                "event: assistant.delta\ndata: " +
                    "x".repeat(1024 * 1024 + 1) +
                    "\n\n",
                chunkSize = 8192
            )
        )

        val listener = runTurn()

        assertEquals(
            listOf<ChatTurnEvent>(ChatTurnEvent.TurnFailed(ChatStreamError.IncompatibleApi)),
            listener.events
        )
    }

    @Test
    fun interactiveApprovalRequestArrivesWithOnlyClosedFields() {
        server.enqueue(
            sse(
                frame("run.started", 1, runId = APPROVAL_RUN_ID) +
                    frame("message.started", 2, messageExtra(), runId = APPROVAL_RUN_ID) +
                    frame("approval.request", 3, approvalExtra(), runId = APPROVAL_RUN_ID) +
                    frame(
                        "assistant.completed",
                        4,
                        completedExtra("Hallo"),
                        runId = APPROVAL_RUN_ID
                    ) +
                    frame("run.completed", 5, runId = APPROVAL_RUN_ID) +
                    frame("done", 6, runId = APPROVAL_RUN_ID)
            )
        )

        val listener = runTurn(interactiveApprovals = true)

        assertEquals(5, listener.events.size)
        assertEquals(
            ChatTurnEvent.RunStarted(APPROVAL_RUN_ID),
            listener.events[0]
        )
        assertEquals(ChatTurnEvent.AssistantStarted, listener.events[1])
        val approval = listener.events[2] as ChatTurnEvent.ApprovalRequested
        assertEquals(APPROVAL_RUN_ID, approval.request.runId)
        assertEquals(REQUEST_ID, approval.request.requestId)
        assertEquals(ApprovalRequest.SAFE_DESCRIPTION, approval.request.description)
        assertEquals(ChatTurnEvent.AssistantCompleted("Hallo"), listener.events[3])
        assertEquals(ChatTurnEvent.TurnCompleted(), listener.events[4])
        assertFalse(listener.events.toString().contains("synthetic-command"))
    }

    @Test
    fun approvalRequestWithoutNegotiatedCapabilityIsIncompatible() {
        server.enqueue(
            sse(
                frame("run.started", 1, runId = APPROVAL_RUN_ID) +
                    frame("message.started", 2, messageExtra(), runId = APPROVAL_RUN_ID) +
                    frame("approval.request", 3, approvalExtra(), runId = APPROVAL_RUN_ID)
            )
        )

        val listener = runTurn(interactiveApprovals = false)

        assertEquals(
            listOf<ChatTurnEvent>(
                ChatTurnEvent.RunStarted(APPROVAL_RUN_ID),
                ChatTurnEvent.AssistantStarted,
                ChatTurnEvent.TurnFailed(ChatStreamError.IncompatibleApi)
            ),
            listener.events
        )
    }

    private fun runTurn(interactiveApprovals: Boolean = false): RecordingListener {
        val listener = RecordingListener()
        val handle: ChatStreamHandle = client.openTurn(
            baseUrl = baseUrl(),
            apiKey = SecretValue(FIXTURE_KEY),
            sessionId = SESSION_ID,
            message = "Hallo",
            interactiveApprovals = interactiveApprovals,
            listener = listener
        )
        assertTrue("stream did not finish", listener.finished.await(10, TimeUnit.SECONDS))
        handle.close()
        return listener
    }

    private fun baseUrl(): String = server.url("/").toString().trimEnd('/')

    private fun sse(body: String, chunkSize: Int = 4096): MockResponse = MockResponse()
        .addHeader("Content-Type", "text/event-stream")
        .setChunkedBody(body, chunkSize)

    private fun successFrames(lineBreak: String): List<String> = listOf(
        frame("run.started", 1, lineBreak = lineBreak),
        frame("message.started", 2, messageExtra(), lineBreak = lineBreak),
        frame("assistant.delta", 3, deltaExtra("Hallo"), lineBreak = lineBreak),
        frame("assistant.completed", 4, completedExtra("Hallo Welt"), lineBreak = lineBreak),
        frame("run.completed", 5, lineBreak = lineBreak),
        frame("done", 6, lineBreak = lineBreak)
    )

    private fun frame(
        name: String,
        seq: Long,
        extra: JSONObject = JSONObject(),
        runId: String = RUN_ID,
        lineBreak: String = "\n"
    ): String {
        val payload = JSONObject()
            .put("session_id", SESSION_ID)
            .put("run_id", runId)
            .put("seq", seq)
            .put("ts", 1000.5)
        for (key in extra.keys()) {
            payload.put(key, extra.get(key))
        }
        return "event: $name$lineBreak" +
            "data: " + payload.toString() + lineBreak + lineBreak
    }

    private fun messageExtra(): JSONObject = JSONObject()
        .put("message", JSONObject().put("id", MSG_ID).put("role", "assistant"))

    private fun deltaExtra(delta: String): JSONObject = JSONObject()
        .put("message_id", MSG_ID)
        .put("delta", delta)

    private fun completedExtra(content: String): JSONObject = JSONObject()
        .put("message_id", MSG_ID)
        .put("content", content)

    private fun approvalExtra(): JSONObject = JSONObject()
        .put("request_id", REQUEST_ID)
        .put("description", ApprovalRequest.SAFE_DESCRIPTION)
        .put("choices", JSONArray().put("once").put("deny"))

    private class RecordingListener : ChatStreamListener {
        val events = mutableListOf<ChatTurnEvent>()
        val finished = CountDownLatch(1)

        override fun onEvent(event: ChatTurnEvent) {
            synchronized(events) {
                events += event
            }
        }

        override fun onFinished() {
            finished.countDown()
        }
    }

    private fun successEvents(): List<ChatTurnEvent> = listOf(
        ChatTurnEvent.RunStarted(RUN_ID),
        ChatTurnEvent.AssistantStarted,
        ChatTurnEvent.AssistantDelta("Hallo"),
        ChatTurnEvent.AssistantCompleted("Hallo Welt"),
        ChatTurnEvent.TurnCompleted()
    )

    private companion object {
        const val FIXTURE_KEY = "fixture-credential"
        const val SESSION_ID = "synthetic-session"
        const val RUN_ID = "run_synthetic"
        const val APPROVAL_RUN_ID = "run_0123456789abcdef0123456789abcdef"
        const val REQUEST_ID = "fedcba9876543210fedcba9876543210"
        const val MSG_ID = "msg_synthetic"
    }
}
