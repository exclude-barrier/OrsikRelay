package io.github.excludebarrier.orsikrelay.network

import io.github.excludebarrier.orsikrelay.domain.chat.ChatStreamListener
import io.github.excludebarrier.orsikrelay.domain.chat.ChatTurnEvent
import io.github.excludebarrier.orsikrelay.domain.connection.HermesConnectionResult
import io.github.excludebarrier.orsikrelay.domain.message.MessageHistoryResult
import io.github.excludebarrier.orsikrelay.domain.message.MessageRole
import io.github.excludebarrier.orsikrelay.domain.profile.ProfileDraft
import io.github.excludebarrier.orsikrelay.domain.profile.SecretValue
import io.github.excludebarrier.orsikrelay.domain.session.SessionListResult
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HermesCoreFixtureCompatTest {

    private lateinit var server: MockWebServer
    private lateinit var certificate: HeldCertificate
    private lateinit var trustedClient: OkHttpClient
    private lateinit var trustedStreamClient: OkHttpClient
    private lateinit var messageClient: HermesMessageClient
    private lateinit var sessionClient: HermesSessionClient
    private lateinit var connectionClient: HermesConnectionClient
    private lateinit var streamClient: HermesSessionStreamClient

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
        trustedStreamClient = trustedClient.newBuilder()
            .protocols(listOf(Protocol.HTTP_1_1))
            .build()
        messageClient = HermesMessageClient(trustedClient)
        sessionClient = HermesSessionClient(trustedClient)
        connectionClient = HermesConnectionClient(trustedClient)
        streamClient = HermesSessionStreamClient(trustedStreamClient)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun toolTurnPageFromCoreKeepsVisibleRolesAndToolRow() {
        val body = fixture("messages_tool.json")
        server.enqueue(jsonResponse(body))

        val result = messageClient.fetch(
            baseUrl(),
            SecretValue(FIXTURE_KEY),
            JSONObject(body).getString("session_id"),
            0
        )

        assertTrue(result is MessageHistoryResult.Success)
        val messages = (result as MessageHistoryResult.Success).page.messages
        assertEquals(
            listOf(
                MessageRole.USER,
                MessageRole.ASSISTANT,
                MessageRole.TOOL,
                MessageRole.ASSISTANT,
                MessageRole.USER,
                MessageRole.ASSISTANT
            ),
            messages.map { it.role }
        )
        assertEquals("terminal", messages.single { it.role == MessageRole.TOOL }.toolName)
        assertEquals("2026", messages[3].content)
    }

    @Test
    fun imageTurnPageFromCoreKeepsPromptAndCountsImageWithoutLeaks() {
        val body = fixture("messages_image.json")
        server.enqueue(jsonResponse(body))

        val result = messageClient.fetch(
            baseUrl(),
            SecretValue(FIXTURE_KEY),
            JSONObject(body).getString("session_id"),
            0
        )

        assertTrue(result is MessageHistoryResult.Success)
        val messages = (result as MessageHistoryResult.Success).page.messages
        val user = messages.first()
        assertEquals(MessageRole.USER, user.role)
        assertEquals("Welche Farbe hat dieses Bild?", user.content)
        assertEquals(1, user.imageCount)
        messages.forEach { message ->
            assertFalse(message.content.contains("/home/"))
            assertFalse(message.content.contains("[Image attached"))
        }
    }

    @Test
    fun sessionsPageFromCoreParsesEveryTitle() {
        val body = fixture("sessions_page.json")
        server.enqueue(jsonResponse(body))

        val result = sessionClient.fetchPage(
            baseUrl = baseUrl(),
            apiKey = SecretValue(FIXTURE_KEY),
            limit = 20,
            offset = 0
        )

        assertTrue(result is SessionListResult.Success)
        val page = (result as SessionListResult.Success).page
        val data = JSONObject(body).getJSONArray("data")
        val expectedTitles = (0 until data.length()).map { index ->
            data.getJSONObject(index).getString("title")
        }
        assertEquals(expectedTitles.size, page.sessions.size)
        assertEquals(expectedTitles, page.sessions.map { it.title })
    }

    @Test
    fun bridgeCapabilitiesNegotiateInteractiveApprovals() {
        server.enqueue(
            jsonResponse(
                JSONObject()
                    .put("status", "ok")
                    .put("platform", "hermes-agent")
                    .put("version", "0.20-test")
                    .toString()
            )
        )
        server.enqueue(jsonResponse(fixture("capabilities.json")))

        val result = connectionClient.probe(
            ProfileDraft(
                profileName = "Fixture",
                baseUrl = server.url("/").toString(),
                apiKey = SecretValue(FIXTURE_KEY)
            )
        )

        assertTrue(result is HermesConnectionResult.Success)
        assertTrue((result as HermesConnectionResult.Success).sessionChatInteractiveApprovals)
    }

    @Test
    fun toolStreamFromCoreCompletesWithTerminalToolActivity() {
        val body = fixture("stream_tool.sse")
        server.enqueue(sseResponse(body))
        val listener = RecordingListener()

        val handle = streamClient.openTurn(
            baseUrl(),
            SecretValue(FIXTURE_KEY),
            sessionId(body),
            "Hallo",
            listener
        )

        assertCompletedWithoutFailure(listener)
        handle.close()
        assertTrue(
            listener.events.any {
                it is ChatTurnEvent.ToolActivity && it.toolName == "terminal"
            }
        )
        assertEquals(
            completedContent(body),
            listener.events.filterIsInstance<ChatTurnEvent.AssistantCompleted>().last().finalText
        )
    }

    @Test
    fun markdownStreamFromCoreCompletesWithFileFinalText() {
        val body = fixture("stream_markdown.sse")
        server.enqueue(sseResponse(body))
        val listener = RecordingListener()

        val handle = streamClient.openTurn(
            baseUrl(),
            SecretValue(FIXTURE_KEY),
            sessionId(body),
            "Hallo",
            listener
        )

        assertCompletedWithoutFailure(listener)
        handle.close()
        assertEquals(
            frames(body).count { it.first == "assistant.delta" },
            listener.events.count { it is ChatTurnEvent.AssistantDelta }
        )
        assertEquals(
            completedContent(body),
            listener.events.filterIsInstance<ChatTurnEvent.AssistantCompleted>().last().finalText
        )
    }

    @Test
    fun imageStreamFromCoreEmitsApprovalAndCompletes() {
        val body = fixture("stream_image.sse")
        server.enqueue(sseResponse(body))
        val listener = RecordingListener()

        val handle = streamClient.openTurn(
            baseUrl = baseUrl(),
            apiKey = SecretValue(FIXTURE_KEY),
            sessionId = sessionId(body),
            message = "Hallo",
            interactiveApprovals = true,
            listener = listener
        )

        assertCompletedWithoutFailure(listener)
        handle.close()
        val approvalFrame = frames(body).single { it.first == "approval.request" }.second
        val approval = listener.events
            .filterIsInstance<ChatTurnEvent.ApprovalRequested>()
            .single()
            .request
        assertEquals(approvalFrame.getString("run_id"), approval.runId)
        assertEquals(approvalFrame.getString("request_id"), approval.requestId)
        assertEquals(approvalFrame.getString("description"), approval.description)
        val choices = approvalFrame.getJSONArray("choices")
        assertEquals(listOf("once", "deny"), (0 until choices.length()).map { choices.getString(it) })
        assertEquals(
            completedContent(body),
            listener.events.filterIsInstance<ChatTurnEvent.AssistantCompleted>().last().finalText
        )
    }

    private fun assertCompletedWithoutFailure(listener: RecordingListener) {
        assertTrue("stream did not finish", listener.finished.await(10, TimeUnit.SECONDS))
        assertTrue(
            "unexpected failure event: ${listener.events.filterIsInstance<ChatTurnEvent.TurnFailed>()}",
            listener.events.none { it is ChatTurnEvent.TurnFailed }
        )
        assertEquals(ChatTurnEvent.TurnCompleted(), listener.events.last())
    }

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResource("hermes-core-0.22/$name")!!.readText()

    private fun frames(body: String): List<Pair<String, JSONObject>> =
        body.split("\n\n").mapNotNull { block ->
            val event = block.lineSequence()
                .firstOrNull { it.startsWith("event: ") }
                ?.removePrefix("event: ")
                ?: return@mapNotNull null
            val data = block.lineSequence()
                .firstOrNull { it.startsWith("data: ") }
                ?.removePrefix("data: ")
                ?: return@mapNotNull null
            event to JSONObject(data)
        }

    private fun sessionId(body: String): String =
        frames(body).first { it.first == "run.started" }.second.getString("session_id")

    private fun completedContent(body: String): String =
        frames(body).first { it.first == "assistant.completed" }.second.getString("content")

    private fun baseUrl(): String = server.url("/").toString().trimEnd('/')

    private fun jsonResponse(body: String): MockResponse = MockResponse()
        .addHeader("Content-Type", "application/json")
        .setBody(body)

    private fun sseResponse(body: String): MockResponse = MockResponse()
        .addHeader("Content-Type", "text/event-stream")
        .setChunkedBody(body, 4096)

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

    private companion object {
        const val FIXTURE_KEY = "fixture-credential"
    }
}
