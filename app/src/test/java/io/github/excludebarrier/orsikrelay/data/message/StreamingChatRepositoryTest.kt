package io.github.excludebarrier.orsikrelay.data.message

import io.github.excludebarrier.orsikrelay.data.profile.ServerProfileMetadataStore
import io.github.excludebarrier.orsikrelay.data.profile.ServerProfileRepository
import io.github.excludebarrier.orsikrelay.domain.chat.ChatMessageValidator
import io.github.excludebarrier.orsikrelay.domain.chat.ChatStreamError
import io.github.excludebarrier.orsikrelay.domain.chat.ChatStreamHandle
import io.github.excludebarrier.orsikrelay.domain.chat.ChatStreamListener
import io.github.excludebarrier.orsikrelay.domain.chat.ChatStreamTransport
import io.github.excludebarrier.orsikrelay.domain.chat.ChatTurnEvent
import io.github.excludebarrier.orsikrelay.domain.profile.SecretValue
import io.github.excludebarrier.orsikrelay.domain.profile.ServerProfile
import io.github.excludebarrier.orsikrelay.security.credential.CredentialStore
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingChatRepositoryTest {

    @Test
    fun blankProfileIdFailsClosedBeforeAnyStoreRead() {
        val metadata = FakeMetadataStore()
        val credentials = FakeCredentialStore()
        var transportCalls = 0

        val repository = repository(
            metadata,
            credentials,
            object : ChatStreamTransport {
                override fun openTurn(
                    baseUrl: String,
                    apiKey: SecretValue,
                    sessionId: String,
                    message: String,
                    listener: ChatStreamListener
                ): ChatStreamHandle {
                    transportCalls += 1
                    return NoopHandle
                }
            }
        )

        val events = collect(repository) { it.streamTurn("   ", SESSION_ID, "Hallo") }

        assertEquals(
            listOf<ChatTurnEvent>(ChatTurnEvent.TurnFailed(ChatStreamError.InvalidInput)),
            events
        )
        assertTrue(metadata.calls.isEmpty())
        assertTrue(credentials.calls.isEmpty())
        assertEquals(0, transportCalls)
    }

    @Test
    fun blankSessionIdFailsClosedBeforeAnyStoreRead() {
        val metadata = FakeMetadataStore()
        val credentials = FakeCredentialStore()

        val events = collect(
            repository(metadata, credentials),
            { it.streamTurn(PROFILE_ID, "", "Hallo") }
        )

        assertEquals(
            listOf<ChatTurnEvent>(ChatTurnEvent.TurnFailed(ChatStreamError.InvalidInput)),
            events
        )
        assertTrue(metadata.calls.isEmpty())
        assertTrue(credentials.calls.isEmpty())
    }

    @Test
    fun blankOrInvalidMessageFailsClosedBeforeAnyStoreOrTransportAccess() {
        listOf(
            "   ",
            "\u0000",
            "a\u0001b",
            "x".repeat(ChatMessageValidator.MAX_MESSAGE_LENGTH + 1)
        ).forEach { message ->
            val metadata = FakeMetadataStore(mutableListOf(profile()))
            val credentials = FakeCredentialStore(
                mutableMapOf(PROFILE_ID to SecretValue(FIXTURE_KEY))
            )
            var transportCalls = 0
            val repository = repository(
                metadata,
                credentials,
                object : ChatStreamTransport {
                    override fun openTurn(
                        baseUrl: String,
                        apiKey: SecretValue,
                        sessionId: String,
                        message: String,
                        listener: ChatStreamListener
                    ): ChatStreamHandle {
                        transportCalls += 1
                        return NoopHandle
                    }
                }
            )

            val events = collect(repository) { it.streamTurn(PROFILE_ID, SESSION_ID, message) }

            assertEquals(
                listOf<ChatTurnEvent>(ChatTurnEvent.TurnFailed(ChatStreamError.InvalidInput)),
                events
            )
            assertTrue(metadata.calls.isEmpty())
            assertTrue(credentials.calls.isEmpty())
            assertEquals(0, transportCalls)
        }
    }

    @Test
    fun readsMetadataAndCredentialSeparatelyThenDelegatesExactArguments() {
        val metadata = FakeMetadataStore(mutableListOf(profile()))
        val credentials = FakeCredentialStore(
            mutableMapOf(PROFILE_ID to SecretValue(FIXTURE_KEY))
        )
        val transport = CapturingTransport()

        val events = collect(
            repository(metadata, credentials, transport)
        ) { it.streamTurn(PROFILE_ID, SESSION_ID, "  Hallo Welt  ") }

        assertEquals(
            listOf<ChatTurnEvent>(
                ChatTurnEvent.RunStarted,
                ChatTurnEvent.TurnCompleted
            ),
            events
        )
        assertEquals(listOf(PROFILE_ID), metadata.calls)
        assertEquals(listOf(PROFILE_ID), credentials.calls)
        assertEquals(1, transport.calls)
        assertEquals(BASE_URL, transport.baseUrl)
        assertEquals(FIXTURE_KEY, transport.apiKey?.useRaw { it })
        assertEquals(SESSION_ID, transport.sessionId)
        assertEquals("Hallo Welt", transport.message)
        events.forEach { event ->
            assertFalse(event.toString().contains(FIXTURE_KEY))
        }
    }

    @Test
    fun missingMetadataIsClosedFailureAndSkipsCredentialRead() {
        val metadata = FakeMetadataStore()
        val credentials = FakeCredentialStore(
            mutableMapOf(PROFILE_ID to SecretValue(FIXTURE_KEY))
        )
        var transportCalls = 0
        val repository = repository(
            metadata,
            credentials,
            object : ChatStreamTransport {
                override fun openTurn(
                    baseUrl: String,
                    apiKey: SecretValue,
                    sessionId: String,
                    message: String,
                    listener: ChatStreamListener
                ): ChatStreamHandle {
                    transportCalls += 1
                    return NoopHandle
                }
            }
        )

        val events = collect(repository) { it.streamTurn(PROFILE_ID, SESSION_ID, "Hallo") }

        assertEquals(
            listOf<ChatTurnEvent>(ChatTurnEvent.TurnFailed(ChatStreamError.InvalidInput)),
            events
        )
        assertEquals(listOf(PROFILE_ID), metadata.calls)
        assertTrue(credentials.calls.isEmpty())
        assertEquals(0, transportCalls)
    }

    @Test
    fun missingOrEmptyCredentialIsClosedFailureAndDoesNotOpenTransport() {
        listOf(null, SecretValue("")).forEach { credential ->
            val metadata = FakeMetadataStore(mutableListOf(profile()))
            val credentials = FakeCredentialStore(
                if (credential == null) mutableMapOf()
                else mutableMapOf(PROFILE_ID to credential)
            )
            var transportCalls = 0
            val repository = repository(
                metadata,
                credentials,
                object : ChatStreamTransport {
                    override fun openTurn(
                        baseUrl: String,
                        apiKey: SecretValue,
                        sessionId: String,
                        message: String,
                        listener: ChatStreamListener
                    ): ChatStreamHandle {
                        transportCalls += 1
                        return NoopHandle
                    }
                }
            )

            val events = collect(repository) { it.streamTurn(PROFILE_ID, SESSION_ID, "Hallo") }

            assertEquals(
                listOf<ChatTurnEvent>(ChatTurnEvent.TurnFailed(ChatStreamError.InvalidInput)),
                events
            )
            assertEquals(0, transportCalls)
        }
    }

    @Test
    fun storeFailuresStayClosedAndDoNotExposeExceptionText() {
        val metadata = FakeMetadataStore().apply { failReads = true }
        val credentials = FakeCredentialStore()

        val events = collect(
            repository(metadata, credentials)
        ) { it.streamTurn(PROFILE_ID, SESSION_ID, "Hallo") }

        assertEquals(
            listOf<ChatTurnEvent>(ChatTurnEvent.TurnFailed(ChatStreamError.InvalidInput)),
            events
        )
        assertFalse(events.toString().contains("synthetic-sensitive-detail"))

        val failingCredentials = FakeCredentialStore(
            mutableMapOf(PROFILE_ID to SecretValue(FIXTURE_KEY))
        ).apply { failReads = true }
        val events2 = collect(
            repository(FakeMetadataStore(mutableListOf(profile())), failingCredentials)
        ) { it.streamTurn(PROFILE_ID, SESSION_ID, "Hallo") }
        assertEquals(
            listOf<ChatTurnEvent>(ChatTurnEvent.TurnFailed(ChatStreamError.InvalidInput)),
            events2
        )
        assertFalse(events2.toString().contains("synthetic-sensitive-detail"))
    }

    @Test
    fun transportOpeningFailureIsClosedNetworkFailureWithoutDetails() {
        val metadata = FakeMetadataStore(mutableListOf(profile()))
        val credentials = FakeCredentialStore(
            mutableMapOf(PROFILE_ID to SecretValue(FIXTURE_KEY))
        )
        val repository = repository(
            metadata,
            credentials,
            object : ChatStreamTransport {
                override fun openTurn(
                    baseUrl: String,
                    apiKey: SecretValue,
                    sessionId: String,
                    message: String,
                    listener: ChatStreamListener
                ): ChatStreamHandle {
                    throw IllegalStateException("synthetic-sensitive-detail")
                }
            }
        )

        val events = collect(repository) { it.streamTurn(PROFILE_ID, SESSION_ID, "Hallo") }

        assertEquals(
            listOf<ChatTurnEvent>(ChatTurnEvent.TurnFailed(ChatStreamError.Network)),
            events
        )
        assertFalse(events.toString().contains("synthetic-sensitive-detail"))
    }

    @Test
    fun synchronousTransportFailureDeliversTerminalEventAndCompletes() {
        val metadata = FakeMetadataStore(mutableListOf(profile()))
        val credentials = FakeCredentialStore(
            mutableMapOf(PROFILE_ID to SecretValue(FIXTURE_KEY))
        )
        val repository = repository(
            metadata,
            credentials,
            object : ChatStreamTransport {
                override fun openTurn(
                    baseUrl: String,
                    apiKey: SecretValue,
                    sessionId: String,
                    message: String,
                    listener: ChatStreamListener
                ): ChatStreamHandle {
                    listener.onEvent(ChatTurnEvent.TurnFailed(ChatStreamError.InvalidInput))
                    listener.onFinished()
                    return NoopHandle
                }
            }
        )

        val events = collect(repository) { it.streamTurn(PROFILE_ID, SESSION_ID, "Hallo") }

        assertEquals(
            listOf<ChatTurnEvent>(ChatTurnEvent.TurnFailed(ChatStreamError.InvalidInput)),
            events
        )
    }

    @Test
    fun deferredEventsAreDeliveredInOrderAndCollectorCompletionClosesHandle() {
        val metadata = FakeMetadataStore(mutableListOf(profile()))
        val credentials = FakeCredentialStore(
            mutableMapOf(PROFILE_ID to SecretValue(FIXTURE_KEY))
        )
        val transport = DeferredTransport(
            listOf(
                ChatTurnEvent.RunStarted,
                ChatTurnEvent.AssistantStarted,
                ChatTurnEvent.AssistantDelta("Hallo"),
                ChatTurnEvent.AssistantCompleted("Hallo Welt"),
                ChatTurnEvent.TurnCompleted
            )
        )

        val events = collect(
            repository(metadata, credentials, transport)
        ) { it.streamTurn(PROFILE_ID, SESSION_ID, "Hallo") }

        assertEquals(
            listOf<ChatTurnEvent>(
                ChatTurnEvent.RunStarted,
                ChatTurnEvent.AssistantStarted,
                ChatTurnEvent.AssistantDelta("Hallo"),
                ChatTurnEvent.AssistantCompleted("Hallo Welt"),
                ChatTurnEvent.TurnCompleted
            ),
            events
        )
        assertTrue(
            "handle not closed after normal completion",
            awaitHandleClose(transport.closeCalls)
        )
    }

    @Test
    fun collectorCancellationClosesTheTransportHandleWithoutHanging() {
        val metadata = FakeMetadataStore(mutableListOf(profile()))
        val credentials = FakeCredentialStore(
            mutableMapOf(PROFILE_ID to SecretValue(FIXTURE_KEY))
        )
        val transport = NeverFinishingTransport()
        val repository = repository(metadata, credentials, transport)

        // NeverFinishingTransport never emits an event, so a cancellation
        // thrown inside the collector lambda could never execute. The
        // collector is therefore started as its own job and canceled from
        // the outside. `launch` only queues the producer job on the
        // `runBlocking` event loop, so a `yield()` first lets it start
        // and reach `openTurn()` (latched by the transport); only then
        // does a cancellation prove that the already-opened handle is
        // closed. Bounded waits, no busy-loop.
        runBlocking {
            val job = launch {
                repository.streamTurn(PROFILE_ID, SESSION_ID, "Hallo").collect { }
            }
            yield()
            withTimeout(2_000) {
                check(
                    transport.opened.await(2, java.util.concurrent.TimeUnit.SECONDS)
                ) {
                    "openTurn() was not reached before cancellation"
                }
            }
            withTimeout(5_000) {
                job.cancelAndJoin()
            }
        }

        assertTrue(
            "handle not closed after collector cancellation",
            awaitHandleClose(transport.closeCalls)
        )
    }

    private fun awaitHandleClose(
        closeCalls: java.util.concurrent.atomic.AtomicInteger
    ): Boolean {
        // Hard 5 s upper bound instead of an unbounded busy-wait: a
        // missing close fails the test quickly instead of hanging.
        runBlocking {
            withTimeout(5_000) {
                while (closeCalls.get() < 1) {
                    delay(10)
                }
            }
        }
        return closeCalls.get() >= 1
    }

    private fun repository(
        metadata: FakeMetadataStore,
        credentials: FakeCredentialStore,
        transport: ChatStreamTransport = object : ChatStreamTransport {
            override fun openTurn(
                baseUrl: String,
                apiKey: SecretValue,
                sessionId: String,
                message: String,
                listener: ChatStreamListener
            ): ChatStreamHandle {
                listener.onEvent(ChatTurnEvent.TurnCompleted)
                listener.onFinished()
                return NoopHandle
            }
        }
    ): StreamingChatRepository {
        val profileRepository = ServerProfileRepository(metadata, credentials) { "unused-id" }
        return StreamingChatRepository(profileRepository, credentials, transport)
    }

    private fun collect(
        repository: StreamingChatRepository,
        block: (StreamingChatRepository) -> kotlinx.coroutines.flow.Flow<ChatTurnEvent>
    ): List<ChatTurnEvent> = runBlocking {
        val collected = mutableListOf<ChatTurnEvent>()
        block(repository).collect { collected += it }
        collected
    }

    private fun profile() = ServerProfile.create(PROFILE_ID, "Fixture", BASE_URL)

    private object NoopHandle : ChatStreamHandle {
        override fun close() {
            // nothing to cancel
        }
    }

    private class CapturingTransport : ChatStreamTransport {
        var calls = 0
        var baseUrl: String? = null
        var apiKey: SecretValue? = null
        var sessionId: String? = null
        var message: String? = null

        override fun openTurn(
            baseUrl: String,
            apiKey: SecretValue,
            sessionId: String,
            message: String,
            listener: ChatStreamListener
        ): ChatStreamHandle {
            calls += 1
            this.baseUrl = baseUrl
            this.apiKey = apiKey
            this.sessionId = sessionId
            this.message = message
            listener.onEvent(ChatTurnEvent.RunStarted)
            listener.onEvent(ChatTurnEvent.TurnCompleted)
            listener.onFinished()
            return NoopHandle
        }
    }

    private class DeferredTransport(private val events: List<ChatTurnEvent>) :
        ChatStreamTransport {
        val closeCalls = java.util.concurrent.atomic.AtomicInteger()
        private val handle = object : ChatStreamHandle {
            override fun close() {
                closeCalls.incrementAndGet()
            }
        }

        override fun openTurn(
            baseUrl: String,
            apiKey: SecretValue,
            sessionId: String,
            message: String,
            listener: ChatStreamListener
        ): ChatStreamHandle {
            Thread {
                events.forEach(listener::onEvent)
                listener.onFinished()
            }.start()
            return handle
        }
    }

    private class NeverFinishingTransport : ChatStreamTransport {
        val closeCalls = java.util.concurrent.atomic.AtomicInteger()
        // Latched at the start of `openTurn()`, before the handle is
        // returned: the test waits on it so its cancellation can never
        // race ahead of the transport open (which would leave no handle
        // to close and prove nothing about leaks).
        val opened = java.util.concurrent.CountDownLatch(1)
        private val handle = object : ChatStreamHandle {
            override fun close() {
                closeCalls.incrementAndGet()
            }
        }

        override fun openTurn(
            baseUrl: String,
            apiKey: SecretValue,
            sessionId: String,
            message: String,
            listener: ChatStreamListener
        ): ChatStreamHandle {
            opened.countDown()
            return handle
        }
    }

    private class FakeMetadataStore(
        val profiles: MutableList<ServerProfile> = mutableListOf()
    ) : ServerProfileMetadataStore {
        val calls = mutableListOf<String>()
        var failReads = false

        override fun save(profile: ServerProfile) {
            profiles.removeAll { it.id == profile.id }
            profiles += profile
        }

        override fun get(profileId: String): ServerProfile? {
            calls += profileId
            if (failReads) throw IllegalStateException("synthetic-sensitive-detail")
            return profiles.firstOrNull { it.id == profileId }
        }

        override fun list(): List<ServerProfile> = profiles.sortedBy { it.id }

        override fun delete(profileId: String) {
            profiles.removeAll { it.id == profileId }
        }
    }

    private class FakeCredentialStore(
        val values: MutableMap<String, SecretValue> = mutableMapOf()
    ) : CredentialStore {
        val calls = mutableListOf<String>()
        var failReads = false

        override fun put(profileId: String, secret: SecretValue) {
            values[profileId] = secret
        }

        override fun get(profileId: String): SecretValue? {
            calls += profileId
            if (failReads) throw IllegalStateException("synthetic-sensitive-detail")
            return values[profileId]
        }

        override fun delete(profileId: String) {
            values.remove(profileId)
        }
    }

    private companion object {
        const val PROFILE_ID = "fixture-profile-id"
        const val SESSION_ID = "fixture-session-id"
        const val BASE_URL = "https://example.invalid/p/android"
        const val FIXTURE_KEY = "fixture-credential"
    }
}
