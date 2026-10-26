package io.github.excludebarrier.orsikrelay.network

import io.github.excludebarrier.orsikrelay.domain.profile.SecretValue
import io.github.excludebarrier.orsikrelay.domain.terminal.TerminalCloseReason
import io.github.excludebarrier.orsikrelay.domain.terminal.TerminalEvent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * JVM tests for the `GET /v1/terminal` WebSocket: the handshake request, the
 * binary/text framing, the input and resize bounds, and the bridge's close
 * and HTTP failure mapping. A TLS [MockWebServer] plays the bridge, because
 * the client refuses plain HTTP.
 */
class HermesTerminalClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: HermesTerminalClient
    private val key = SecretValue("fixture_key_123")

    @Before
    fun setUp() {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientCertificates = HandshakeCertificates.Builder()
            .addTrustedCertificate(certificate.certificate)
            .build()
        server = MockWebServer().apply {
            useHttps(serverCertificates.sslSocketFactory(), false)
            start()
        }
        client = HermesTerminalClient(
            OkHttpClient.Builder()
                .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
                .build()
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private val base get() = server.url("/p").toString().trimEnd('/')

    private fun json(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun capabilities(
        feature: Boolean = true,
        method: String = "GET",
        path: String = "/v1/terminal"
    ) = """
        {"features":{"terminal":$feature},
         "endpoints":{"terminal":{"method":"$method","path":"$path"}}}
    """.trimIndent()

    private fun upgrade(socket: TestSocket) = MockResponse().withWebSocketUpgrade(socket)

    @Test
    fun supportsNeedsTheFeatureAndTheExactEndpoint() {
        server.enqueue(json(capabilities()))

        assertTrue(client.supports(base, key))

        val request = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(request)
        assertEquals("GET", request!!.method)
        assertEquals("/p/v1/capabilities", request.requestUrl!!.encodedPath)
        assertEquals("Bearer fixture_key_123", request.getHeader("Authorization"))
        // The browser-style Origin is what the bridge rejects.
        assertNull(request.getHeader("Origin"))
    }

    @Test
    fun openSendsTheBearerKeyToTheTerminalPathWithoutOriginOrQuery() {
        val serverSocket = TestSocket()
        server.enqueue(upgrade(serverSocket))

        val connection = client.open(base, key, Events().listener)
        assertNotNull(connection)

        val request = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(request)
        assertEquals("GET", request!!.method)
        assertEquals("/p/v1/terminal", request.requestUrl!!.encodedPath)
        assertNull(request.requestUrl!!.query)
        assertEquals("Bearer fixture_key_123", request.getHeader("Authorization"))
        assertNull(request.getHeader("Origin"))

        connection!!.close()
        serverSocket.awaitClosed()
    }

    @Test
    fun binaryFramesArriveAsOutputWithExactBytesInOrder() {
        val first = byteArrayOf(0x00, 0x1B, '['.code.toByte(), 'A'.code.toByte(), 0xC3.toByte())
        val second = byteArrayOf(0xA9.toByte(), '\n'.code.toByte(), 0x7F, 0xFF.toByte())
        val socket = TestSocket { webSocket ->
            webSocket.send(first.toByteString())
            webSocket.send(second.toByteString())
        }
        server.enqueue(upgrade(socket))
        val events = Events()

        val connection = client.open(base, key, events.listener)!!

        val firstEvent = events.next()
        assertTrue("first event was $firstEvent", firstEvent is TerminalEvent.Output)
        assertArrayEquals(first, (firstEvent as TerminalEvent.Output).bytes)
        val secondEvent = events.next()
        assertTrue("second event was $secondEvent", secondEvent is TerminalEvent.Output)
        assertArrayEquals(second, (secondEvent as TerminalEvent.Output).bytes)
        events.assertNoEvent()
        assertTrue("the open was reported", events.opened)

        connection.close()
        socket.awaitClosed()
    }

    @Test
    fun sendSendsBinaryAndResizeSendsTheExactControlMessage() {
        val socket = TestSocket()
        server.enqueue(upgrade(socket))
        val connection = client.open(base, key, Events().listener)!!
        socket.awaitOpen()

        assertTrue(connection.send(byteArrayOf(0x00, 0x68, 0x69, 0xFF.toByte())))
        assertArrayEquals(byteArrayOf(0x00, 0x68, 0x69, 0xFF.toByte()), socket.nextBinary())

        assertTrue(connection.resize(120, 40))
        assertEquals("""{"type":"resize","cols":120,"rows":40}""", socket.nextText())

        connection.close()
        socket.awaitClosed()
    }

    @Test
    fun resizeRejectsSizesOutsideTheBridgeBoundsAndSendRejectsMoreThan64KiB() {
        val socket = TestSocket()
        server.enqueue(upgrade(socket))
        val connection = client.open(base, key, Events().listener)!!
        socket.awaitOpen()

        assertFalse(connection.resize(0, 24))
        assertFalse(connection.resize(501, 24))
        assertFalse(connection.resize(80, 0))
        assertFalse(connection.resize(80, 301))
        assertFalse(connection.resize(-1, -1))

        assertTrue(connection.resize(HermesTerminalClient.MAX_COLUMNS, HermesTerminalClient.MAX_ROWS))
        assertEquals(
            """{"type":"resize","cols":500,"rows":300}""",
            socket.nextText()
        )

        assertFalse(connection.send(ByteArray(HermesTerminalClient.MAX_INPUT_BYTES + 1)))
        assertTrue(connection.send(ByteArray(HermesTerminalClient.MAX_INPUT_BYTES)))
        assertEquals(HermesTerminalClient.MAX_INPUT_BYTES, socket.nextBinary().size)

        connection.close()
        socket.awaitClosed()
    }

    @Test
    fun serverCloseCodesMapToTerminalCloseReasons() {
        val cases = listOf(
            1000 to TerminalCloseReason.Exited,
            4401 to TerminalCloseReason.Unauthorized,
            4403 to TerminalCloseReason.Disabled,
            1011 to TerminalCloseReason.Disconnected,
            1008 to TerminalCloseReason.Disconnected
        )
        for ((code, expected) in cases) {
            server.enqueue(upgrade(TestSocket { webSocket -> webSocket.close(code, "bye") }))
            val events = Events()

            client.open(base, key, events.listener)

            val closed = events.next()
            assertTrue("code $code delivered $closed", closed is TerminalEvent.Closed)
            assertEquals("code $code", expected, (closed as TerminalEvent.Closed).reason)
            events.assertNoEvent()
        }
    }

    @Test
    fun httpHandshakeFailuresMapToTerminalCloseReasons() {
        val cases = listOf(
            401 to TerminalCloseReason.Unauthorized,
            403 to TerminalCloseReason.Disabled,
            429 to TerminalCloseReason.Busy,
            503 to TerminalCloseReason.Disconnected,
            500 to TerminalCloseReason.Disconnected
        )
        for ((status, expected) in cases) {
            server.enqueue(json("""{"error":{"code":"x"}}""", status))
            val events = Events()

            assertNotNull(client.open(base, key, events.listener))

            val closed = events.next()
            assertTrue("status $status delivered $closed", closed is TerminalEvent.Closed)
            assertEquals("status $status", expected, (closed as TerminalEvent.Closed).reason)
            events.assertNoEvent()
        }
    }

    @Test
    fun aTextFrameFromTheServerIsADisconnect() {
        val socket = TestSocket { webSocket -> webSocket.send("not binary") }
        server.enqueue(upgrade(socket))
        val events = Events()

        client.open(base, key, events.listener)

        val closed = events.next()
        assertTrue("expected Closed, got $closed", closed is TerminalEvent.Closed)
        assertEquals(TerminalCloseReason.Disconnected, (closed as TerminalEvent.Closed).reason)
        events.assertNoEvent()
        socket.awaitClosed()
    }

    @Test
    fun closedIsDeliveredExactlyOnceAndNothingFollowsIt() {
        val socket = TestSocket { webSocket ->
            webSocket.send(byteArrayOf(1, 2, 3).toByteString())
            webSocket.close(1000, "done")
        }
        server.enqueue(upgrade(socket))
        val events = Events()

        val connection = client.open(base, key, events.listener)!!

        val output = events.next()
        assertTrue("expected Output, got $output", output is TerminalEvent.Output)
        assertArrayEquals(byteArrayOf(1, 2, 3), (output as TerminalEvent.Output).bytes)
        val closed = events.next()
        assertTrue("expected Closed, got $closed", closed is TerminalEvent.Closed)
        assertEquals(TerminalCloseReason.Exited, (closed as TerminalEvent.Closed).reason)

        // No second Closed and no output after it.
        events.assertNoEvent()

        assertFalse(connection.send(byteArrayOf(9)))
        assertFalse(connection.resize(80, 24))
        connection.close()
        events.assertNoEvent()
        socket.awaitClosed()
    }

    @Test
    fun callsAfterCloseAreRejected() {
        val socket = TestSocket()
        server.enqueue(upgrade(socket))
        val connection = client.open(base, key, Events().listener)!!
        socket.awaitOpen()

        connection.close()

        assertFalse(connection.send(byteArrayOf(1)))
        assertFalse(connection.resize(80, 24))
        // Closing twice is harmless.
        connection.close()
        socket.awaitClosed()
    }

    /** The bridge side of the socket: records what the client sends. */
    private class TestSocket(
        private val onOpenAction: (WebSocket) -> Unit = {}
    ) : WebSocketListener() {
        private val opened = CountDownLatch(1)
        private val closed = CountDownLatch(1)
        private val binaryFrames = LinkedBlockingQueue<ByteArray>()
        private val textFrames = LinkedBlockingQueue<String>()

        override fun onOpen(webSocket: WebSocket, response: Response) {
            opened.countDown()
            onOpenAction(webSocket)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            // The bridge (aiohttp) completes the close handshake itself.
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            closed.countDown()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            closed.countDown()
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            binaryFrames.offer(bytes.toByteArray())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            textFrames.offer(text)
        }

        fun awaitOpen() {
            assertTrue("the server socket never opened", opened.await(5, TimeUnit.SECONDS))
        }

        fun awaitClosed() {
            assertTrue("the server socket never closed", closed.await(5, TimeUnit.SECONDS))
        }

        fun nextBinary(): ByteArray {
            val bytes = binaryFrames.poll(5, TimeUnit.SECONDS)
            assertNotNull("no binary frame from the client", bytes)
            return bytes!!
        }

        fun nextText(): String {
            val text = textFrames.poll(5, TimeUnit.SECONDS)
            assertNotNull("no text frame from the client", text)
            return text!!
        }
    }

    private class Events {
        private val queue = LinkedBlockingQueue<TerminalEvent>()

        /** The open notice is recorded apart; the queue holds output and the close. */
        @Volatile
        var opened = false

        val listener: (TerminalEvent) -> Unit = { if (it == TerminalEvent.Opened) opened = true else queue.offer(it) }

        fun next(timeoutMillis: Long = 5_000): TerminalEvent {
            val event = queue.poll(timeoutMillis, TimeUnit.MILLISECONDS)
            assertNotNull("no terminal event within ${timeoutMillis}ms", event)
            return event!!
        }

        fun assertNoEvent(timeoutMillis: Long = 300) {
            val event = queue.poll(timeoutMillis, TimeUnit.MILLISECONDS)
            assertNull("unexpected terminal event: $event", event)
        }
    }
}
