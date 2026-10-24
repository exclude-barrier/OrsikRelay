package io.github.excludebarrier.orsikrelay.data.message

import io.github.excludebarrier.orsikrelay.data.profile.ServerProfileMetadataStore
import io.github.excludebarrier.orsikrelay.data.profile.ServerProfileRepository
import io.github.excludebarrier.orsikrelay.domain.chat.ChatMessageValidator
import io.github.excludebarrier.orsikrelay.domain.chat.ChatOutboundMessage
import io.github.excludebarrier.orsikrelay.domain.chat.ChatStreamError
import io.github.excludebarrier.orsikrelay.domain.chat.ChatStreamHandle
import io.github.excludebarrier.orsikrelay.domain.chat.ChatStreamListener
import io.github.excludebarrier.orsikrelay.domain.chat.ChatStreamResumeState
import io.github.excludebarrier.orsikrelay.domain.chat.ChatStreamTransport
import io.github.excludebarrier.orsikrelay.domain.chat.ChatTurnEvent
import io.github.excludebarrier.orsikrelay.domain.profile.SecretValue
import io.github.excludebarrier.orsikrelay.domain.profile.ServerProfile
import io.github.excludebarrier.orsikrelay.domain.run.RunApprovalCapabilitySource
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
                    interactiveApprovals: Boolean,
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
                        interactiveApprovals: Boolean,
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
                ChatTurnEvent.RunStarted("run_synthetic"),
                ChatTurnEvent.TurnCompleted()
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
        assertFalse(transport.interactiveApprovals ?: true)
        events.forEach { event ->
            assertFalse(event.toString().contains(FIXTURE_KEY))
        }
    }

    @Test
    fun confirmedCapabilityIsPassedToTransport() {
        val metadata = FakeMetadataStore(mutableListOf(profile()))
        val credentials = FakeCredentialStore(
            mutableMapOf(PROFILE_ID to SecretValue(FIXTURE_KEY))
        )
        val transport = CapturingTransport()
        val repository = repository(
            metadata = metadata,
            credentials = credentials,
            transport = transport,
            capabilitySource = RunApprovalCapabilitySource { true }
        )

        collect(repository) { it.streamTurn(PROFILE_ID, SESSION_ID, "Hallo") }

        assertTrue(transport.interactiveApprovals == true)
    }

    @Test
    fun capabilityReadFailureKeepsTransportFailClosed() {
        val metadata = FakeMetadataStore(mutableListOf(profile()))
        val credentials = FakeCredentialStore(
            mutableMapOf(PROFILE_ID to SecretValue(FIXTURE_KEY))
        )
        val transport = CapturingTransport()
        val repository = repository(
            metadata = metadata,
            credentials = credentials,
            transport = transport,
            capabilitySource = RunApprovalCapabilitySource {
                throw IllegalStateException("synthetic-sensitive-detail")
            }
        )

        collect(repository) { it.streamTurn(PROFILE_ID, SESSION_ID, "Hallo") }

        assertFalse(transport.interactiveApprovals ?: true)
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
                    interactiveApprovals: Boolean,
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
                        interactiveApprovals: Boolean,
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
                    interactiveApprovals: Boolean,
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
                    interactiveApprovals: Boolean,
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
                ChatTurnEvent.RunStarted("run_synthetic"),
                ChatTurnEvent.AssistantStarted,
                ChatTurnEvent.AssistantDelta("Hallo"),
                ChatTurnEvent.AssistantCompleted("Hallo Welt"),
                ChatTurnEvent.TurnCompleted()
            )
        )

        val events = collect(
            repository(metadata, credentials, transport)
        ) { it.streamTurn(PROFILE_ID, SESSION_ID, "Hallo") }

        assertEquals(
            listOf<ChatTurnEvent>(
                ChatTurnEvent.RunStarted("run_synthetic"),
                ChatTurnEvent.AssistantStarted,
                ChatTurnEvent.AssistantDelta("Hallo"),
                ChatTurnEvent.AssistantCompleted("Hallo Welt"),
                ChatTurnEvent.TurnCompleted()
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

    // ------------------------------------------------------------------
    // Re-attach across transient stream failures
    // ------------------------------------------------------------------

    @Test
    fun transientNetworkFailureReattachesToSameRunWithConfirmedState() {
        val metadata = FakeMetadataStore(mutableListOf(profile()))
        val credentials = FakeCredentialStore(
            mutableMapOf(PROFILE_ID to SecretValue(FIXTURE_KEY))
        )
        val transport = ReattachTransport(
            firstAttempt = { listener ->
                listener.onEvent(ChatTurnEvent.RunStarted("run_p4"))
                listener.onEvent(ChatTurnEvent.TurnFailed(ChatStreamError.Network))
                listener.onFinished()
                HandleWithSnapshot(snapshot(lastSeq = 2L))
            },
            resumeAttempt = { listener, _ ->
                listener.onEvent(ChatTurnEvent.AssistantDelta("fortsetzung"))
                listener.onEvent(ChatTurnEvent.TurnCompleted())
                listener.onFinished()
                HandleWithSnapshot(snapshot(lastSeq = 3L, runCompleted = true))
            }
        )
        val repository = repository(
            metadata, credentials, transport,
            reconnectBackoffMs = longArrayOf(1L)
        )

        val events = collect(repository) { it.streamTurn(PROFILE_ID, SESSION_ID, "Hallo") }

        assertEquals(
            listOf<ChatTurnEvent>(
                ChatTurnEvent.RunStarted("run_p4"),
                ChatTurnEvent.StreamReconnecting,
                ChatTurnEvent.AssistantDelta("fortsetzung"),
                ChatTurnEvent.TurnCompleted()
            ),
            events
        )
        assertEquals(1, transport.openTurnCalls)
        assertEquals(1, transport.openResumeCalls)
        assertEquals("run_p4", transport.lastRunId)
        assertEquals(2L, transport.lastResume?.lastSeq)
    }

    @Test
    fun definitiveFailureIsNeverReattached() {
        val metadata = FakeMetadataStore(mutableListOf(profile()))
        val credentials = FakeCredentialStore(
            mutableMapOf(PROFILE_ID to SecretValue(FIXTURE_KEY))
        )
        val transport = ReattachTransport(
            firstAttempt = { listener ->
                listener.onEvent(ChatTurnEvent.RunStarted("run_p4"))
                listener.onEvent(ChatTurnEvent.TurnFailed(ChatStreamError.InvalidInput))
                listener.onFinished()
                HandleWithSnapshot(snapshot())
            },
            resumeAttempt = { _, _ -> throw AssertionError("openResume must not be called") }
        )
        val repository = repository(metadata, credentials, transport)

        val events = collect(repository) { it.streamTurn(PROFILE_ID, SESSION_ID, "Hallo") }

        assertEquals(
            listOf<ChatTurnEvent>(
                ChatTurnEvent.RunStarted("run_p4"),
                ChatTurnEvent.TurnFailed(ChatStreamError.InvalidInput)
            ),
            events
        )
        assertEquals(0, transport.openResumeCalls)
    }

    @Test
    fun networkFailureWithoutConfirmedRunSettlesWithoutReattach() {
        val metadata = FakeMetadataStore(mutableListOf(profile()))
        val credentials = FakeCredentialStore(
            mutableMapOf(PROFILE_ID to SecretValue(FIXTURE_KEY))
        )
        val transport = ReattachTransport(
            firstAttempt = { listener ->
                listener.onEvent(ChatTurnEvent.TurnFailed(ChatStreamError.Network))
                listener.onFinished()
                HandleWithSnapshot(null)
            },
            resumeAttempt = { _, _ -> throw AssertionError("openResume must not be called") }
        )
        val repository = repository(metadata, credentials, transport)

        val events = collect(repository) { it.streamTurn(PROFILE_ID, SESSION_ID, "Hallo") }

        assertEquals(
            listOf<ChatTurnEvent>(ChatTurnEvent.TurnFailed(ChatStreamError.Network)),
            events
        )
        assertEquals(0, transport.openResumeCalls)
    }

    @Test
    fun terminalSnapshotIsNotReattached() {
        val metadata = FakeMetadataStore(mutableListOf(profile()))
        val credentials = FakeCredentialStore(
            mutableMapOf(PROFILE_ID to SecretValue(FIXTURE_KEY))
        )
        val transport = ReattachTransport(
            firstAttempt = { listener ->
                listener.onEvent(ChatTurnEvent.RunStarted("run_p4"))
                listener.onEvent(ChatTurnEvent.TurnFailed(ChatStreamError.Network))
                listener.onFinished()
                HandleWithSnapshot(snapshot(sawError = true))
            },
            resumeAttempt = { _, _ -> throw AssertionError("openResume must not be called") }
        )
        val repository = repository(metadata, credentials, transport)

        val events = collect(repository) { it.streamTurn(PROFILE_ID, SESSION_ID, "Hallo") }

        assertEquals(
            listOf<ChatTurnEvent>(
                ChatTurnEvent.RunStarted("run_p4"),
                ChatTurnEvent.TurnFailed(ChatStreamError.Network)
            ),
            events
        )
        assertEquals(0, transport.openResumeCalls)
    }

    @Test
    fun resumeFailureSettlesTurnWithSingleTerminalEvent() {
        val metadata = FakeMetadataStore(mutableListOf(profile()))
        val credentials = FakeCredentialStore(
            mutableMapOf(PROFILE_ID to SecretValue(FIXTURE_KEY))
        )
        val transport = ReattachTransport(
            firstAttempt = { listener ->
                listener.onEvent(ChatTurnEvent.RunStarted("run_p4"))
                listener.onEvent(ChatTurnEvent.TurnFailed(ChatStreamError.Network))
                listener.onFinished()
                HandleWithSnapshot(snapshot(lastSeq = 2L))
            },
            resumeAttempt = { listener, _ ->
                // A transport without re-attach support settles with the
                // closed ResumeUnavailable reason (default contract).
                listener.onEvent(ChatTurnEvent.TurnFailed(ChatStreamError.ResumeUnavailable))
                listener.onFinished()
                HandleWithSnapshot(snapshot(lastSeq = 2L))
            }
        )
        val repository = repository(
            metadata, credentials, transport,
            reconnectBackoffMs = longArrayOf(1L)
        )

        val events = collect(repository) { it.streamTurn(PROFILE_ID, SESSION_ID, "Hallo") }

        assertEquals(
            listOf<ChatTurnEvent>(
                ChatTurnEvent.RunStarted("run_p4"),
                ChatTurnEvent.StreamReconnecting,
                ChatTurnEvent.TurnFailed(ChatStreamError.ResumeUnavailable)
            ),
            events
        )
        assertEquals(1, transport.openResumeCalls)
    }

    @Test
    fun reattachAlwaysTargetsTheSameRunAsTheResumeStateItReplays() {
        // Run-id boundary invariant: the run id of the re-attach
        // request and the run id of the state it resumes are the same
        // confirmed run. The transport additionally rejects a mismatch
        // closed, so a foreign run can never be replayed.
        val metadata = FakeMetadataStore(mutableListOf(profile()))
        val credentials = FakeCredentialStore(
            mutableMapOf(PROFILE_ID to SecretValue(FIXTURE_KEY))
        )
        val transport = ReattachTransport(
            firstAttempt = { listener ->
                listener.onEvent(ChatTurnEvent.RunStarted("run_p4"))
                listener.onEvent(ChatTurnEvent.TurnFailed(ChatStreamError.Network))
                listener.onFinished()
                HandleWithSnapshot(snapshot(lastSeq = 2L))
            },
            resumeAttempt = { listener, _ ->
                listener.onEvent(ChatTurnEvent.TurnCompleted())
                listener.onFinished()
                HandleWithSnapshot(snapshot(lastSeq = 3L, runCompleted = true))
            }
        )
        val repository = repository(
            metadata, credentials, transport,
            reconnectBackoffMs = longArrayOf(1L)
        )

        val events = collect(repository) { it.streamTurn(PROFILE_ID, SESSION_ID, "Hallo") }

        assertEquals(
            listOf<ChatTurnEvent>(
                ChatTurnEvent.RunStarted("run_p4"),
                ChatTurnEvent.StreamReconnecting,
                ChatTurnEvent.TurnCompleted()
            ),
            events
        )
        assertEquals(1, transport.openTurnCalls)
        assertEquals(1, transport.openResumeCalls)
        assertEquals("run_p4", transport.lastRunId)
        assertEquals(
            "the re-attach must target exactly the run of its resume state",
            transport.lastRunId,
            transport.lastResume?.runId
        )
    }

    @Test
    fun reconnectBackoffClampsToTheLastIntervalAndNeverRetriesAtZero() {
        // Backoff bound: an `attempt` outside the ladder (only
        // possible when a caller pairs a larger attempt bound with a
        // shorter ladder) clamps to the LAST interval instead of
        // falling back to 0 ms, so no aggressive retry spin exists.
        val ladder = longArrayOf(500L, 1_000L, 2_000L)
        assertEquals(500L, StreamingChatRepository.reconnectBackoffMsFor(0, ladder))
        assertEquals(1_000L, StreamingChatRepository.reconnectBackoffMsFor(1, ladder))
        assertEquals(2_000L, StreamingChatRepository.reconnectBackoffMsFor(2, ladder))
        assertEquals(
            "the first attempt past the ladder must clamp, not drop to 0 ms",
            2_000L,
            StreamingChatRepository.reconnectBackoffMsFor(3, ladder)
        )
        assertEquals(2_000L, StreamingChatRepository.reconnectBackoffMsFor(1_000, ladder))
        // The production ladder has exactly one interval per allowed
        // attempt, so the clamp is unreachable with the defaults.
        assertEquals(
            StreamingChatRepository.MAX_RECONNECT_ATTEMPTS,
            StreamingChatRepository.RECONNECT_BACKOFF_MS.size
        )
        assertTrue(StreamingChatRepository.RECONNECT_BACKOFF_MS.all { it > 0L })
        // Only a deliberately empty ladder yields 0 ms - and even then
        // the number of attempts stays bounded by maxReconnectAttempts.
        assertEquals(0L, StreamingChatRepository.reconnectBackoffMsFor(0, longArrayOf()))
    }

    @Test
    fun reattachAttemptsAreBoundedAndStillTerminate() {
        val metadata = FakeMetadataStore(mutableListOf(profile()))
        val credentials = FakeCredentialStore(
            mutableMapOf(PROFILE_ID to SecretValue(FIXTURE_KEY))
        )
        val transport = ReattachTransport(
            firstAttempt = { listener ->
                listener.onEvent(ChatTurnEvent.RunStarted("run_p4"))
                listener.onEvent(ChatTurnEvent.TurnFailed(ChatStreamError.Network))
                listener.onFinished()
                HandleWithSnapshot(snapshot(lastSeq = 2L))
            },
            resumeAttempt = { listener, _ ->
                listener.onEvent(ChatTurnEvent.TurnFailed(ChatStreamError.Network))
                listener.onFinished()
                HandleWithSnapshot(snapshot(lastSeq = 2L))
            }
        )
        val repository = repository(
            metadata, credentials, transport,
            maxReconnectAttempts = 2,
            reconnectBackoffMs = longArrayOf(1L, 1L)
        )

        val events = collect(repository) { it.streamTurn(PROFILE_ID, SESSION_ID, "Hallo") }

        assertEquals(
            listOf<ChatTurnEvent>(
                ChatTurnEvent.RunStarted("run_p4"),
                ChatTurnEvent.StreamReconnecting,
                ChatTurnEvent.StreamReconnecting,
                ChatTurnEvent.TurnFailed(ChatStreamError.Network)
            ),
            events
        )
        assertEquals(1, transport.openTurnCalls)
        assertEquals(2, transport.openResumeCalls)
    }

    @Test
    fun reattachesThatAdvanceTheRunDoNotUseUpTheAttemptBound() {
        // The bound limits failures in a row, not interruptions per turn: a
        // long run whose every re-attach advances the confirmed sequence
        // before the next drop must keep going past maxReconnectAttempts.
        val metadata = FakeMetadataStore(mutableListOf(profile()))
        val credentials = FakeCredentialStore(
            mutableMapOf(PROFILE_ID to SecretValue(FIXTURE_KEY))
        )
        val transport = ReattachTransport(
            firstAttempt = { listener ->
                listener.onEvent(ChatTurnEvent.RunStarted("run_p4"))
                listener.onEvent(ChatTurnEvent.TurnFailed(ChatStreamError.Network))
                listener.onFinished()
                HandleWithSnapshot(snapshot(lastSeq = 2L))
            },
            resumeAttempt = { listener, resume ->
                val next = resume.lastSeq + 1
                if (next < 7L) {
                    listener.onEvent(ChatTurnEvent.TurnFailed(ChatStreamError.Network))
                    listener.onFinished()
                    HandleWithSnapshot(snapshot(lastSeq = next))
                } else {
                    listener.onEvent(ChatTurnEvent.TurnCompleted())
                    listener.onFinished()
                    HandleWithSnapshot(snapshot(lastSeq = next, runCompleted = true))
                }
            }
        )
        val repository = repository(
            metadata, credentials, transport,
            maxReconnectAttempts = 2,
            reconnectBackoffMs = longArrayOf(1L, 1L)
        )

        val events = collect(repository) { it.streamTurn(PROFILE_ID, SESSION_ID, "Hallo") }

        assertEquals(
            listOf<ChatTurnEvent>(ChatTurnEvent.RunStarted("run_p4")) +
                List(5) { ChatTurnEvent.StreamReconnecting } +
                ChatTurnEvent.TurnCompleted(),
            events
        )
        assertEquals(5, transport.openResumeCalls)
    }

    @Test
    fun resumeFailureWithoutAttemptSnapshotFallsBackToTheConfirmedState() {
        // Production shape of an HTTP status failure on the re-attach GET
        // (5xx -> Network): the stream was never bound, so that attempt's
        // handle carries no snapshot of its own. The confirmed state of the
        // interrupted attempt is still exact (no frame was processed), so
        // the loop must keep re-attaching to the same run from that state
        // instead of settling the turn on the transport hiccup.
        val metadata = FakeMetadataStore(mutableListOf(profile()))
        val credentials = FakeCredentialStore(
            mutableMapOf(PROFILE_ID to SecretValue(FIXTURE_KEY))
        )
        var resumeAttempts = 0
        val transport = ReattachTransport(
            firstAttempt = { listener ->
                listener.onEvent(ChatTurnEvent.RunStarted("run_p4"))
                listener.onEvent(ChatTurnEvent.TurnFailed(ChatStreamError.Network))
                listener.onFinished()
                HandleWithSnapshot(snapshot(lastSeq = 2L))
            },
            resumeAttempt = { listener, _ ->
                resumeAttempts += 1
                if (resumeAttempts == 1) {
                    listener.onEvent(ChatTurnEvent.TurnFailed(ChatStreamError.Network))
                    listener.onFinished()
                    HandleWithSnapshot(null)
                } else {
                    listener.onEvent(ChatTurnEvent.AssistantDelta("fortsetzung"))
                    listener.onEvent(ChatTurnEvent.TurnCompleted())
                    listener.onFinished()
                    HandleWithSnapshot(snapshot(lastSeq = 3L, runCompleted = true))
                }
            }
        )
        val repository = repository(
            metadata, credentials, transport,
            reconnectBackoffMs = longArrayOf(1L)
        )

        val events = collect(repository) { it.streamTurn(PROFILE_ID, SESSION_ID, "Hallo") }

        assertEquals(
            listOf<ChatTurnEvent>(
                ChatTurnEvent.RunStarted("run_p4"),
                ChatTurnEvent.StreamReconnecting,
                ChatTurnEvent.StreamReconnecting,
                ChatTurnEvent.AssistantDelta("fortsetzung"),
                ChatTurnEvent.TurnCompleted()
            ),
            events
        )
        // No second POST, no second user message: the turn is only
        // re-attached, and the confirmed run and its confirmed sequence
        // survive the snapshot-less failure unchanged.
        assertEquals(1, transport.openTurnCalls)
        assertEquals(2, transport.openResumeCalls)
        assertEquals("run_p4", transport.lastRunId)
        assertEquals(2L, transport.lastResume?.lastSeq)
    }

    @Test
    fun synchronousResumeFailureRetriesWithTheConfirmedState() {
        val metadata = FakeMetadataStore(mutableListOf(profile()))
        val credentials = FakeCredentialStore(
            mutableMapOf(PROFILE_ID to SecretValue(FIXTURE_KEY))
        )
        var resumeAttempts = 0
        val transport = ReattachTransport(
            firstAttempt = { listener ->
                listener.onEvent(ChatTurnEvent.RunStarted("run_p4"))
                listener.onEvent(ChatTurnEvent.TurnFailed(ChatStreamError.Network))
                listener.onFinished()
                HandleWithSnapshot(snapshot(lastSeq = 2L))
            },
            resumeAttempt = { listener, _ ->
                resumeAttempts += 1
                if (resumeAttempts == 1) {
                    throw IllegalStateException("synthetic-sensitive-detail")
                }
                listener.onEvent(ChatTurnEvent.TurnCompleted())
                listener.onFinished()
                HandleWithSnapshot(snapshot(lastSeq = 3L, runCompleted = true))
            }
        )
        val repository = repository(
            metadata, credentials, transport,
            reconnectBackoffMs = longArrayOf(1L)
        )

        val events = collect(repository) { it.streamTurn(PROFILE_ID, SESSION_ID, "Hallo") }

        assertEquals(
            listOf<ChatTurnEvent>(
                ChatTurnEvent.RunStarted("run_p4"),
                ChatTurnEvent.StreamReconnecting,
                ChatTurnEvent.StreamReconnecting,
                ChatTurnEvent.TurnCompleted()
            ),
            events
        )
        assertEquals(1, transport.openTurnCalls)
        assertEquals(2, transport.openResumeCalls)
        assertEquals("run_p4", transport.lastRunId)
        assertEquals(2L, transport.lastResume?.lastSeq)
        assertFalse(events.toString().contains("synthetic-sensitive-detail"))
    }

    @Test
    fun rejectedCredentialOnReattachFailsClosedWithoutRetry() {
        // A credential rejected (401/403) or revoked between the attempts
        // is definitive for the turn: exactly one re-attach, then fail
        // closed - never a retry ladder and never a second POST.
        val metadata = FakeMetadataStore(mutableListOf(profile()))
        val credentials = FakeCredentialStore(
            mutableMapOf(PROFILE_ID to SecretValue(FIXTURE_KEY))
        )
        val transport = ReattachTransport(
            firstAttempt = { listener ->
                listener.onEvent(ChatTurnEvent.RunStarted("run_p4"))
                listener.onEvent(ChatTurnEvent.TurnFailed(ChatStreamError.Network))
                listener.onFinished()
                HandleWithSnapshot(snapshot(lastSeq = 2L))
            },
            resumeAttempt = { listener, _ ->
                listener.onEvent(ChatTurnEvent.TurnFailed(ChatStreamError.InvalidCredentials))
                listener.onFinished()
                HandleWithSnapshot(null)
            }
        )
        val repository = repository(
            metadata, credentials, transport,
            reconnectBackoffMs = longArrayOf(1L)
        )

        val events = collect(repository) { it.streamTurn(PROFILE_ID, SESSION_ID, "Hallo") }

        assertEquals(
            listOf<ChatTurnEvent>(
                ChatTurnEvent.RunStarted("run_p4"),
                ChatTurnEvent.StreamReconnecting,
                ChatTurnEvent.TurnFailed(ChatStreamError.InvalidCredentials)
            ),
            events
        )
        assertEquals(1, transport.openTurnCalls)
        assertEquals(1, transport.openResumeCalls)
        assertEquals("run_p4", transport.lastRunId)
    }

    @Test
    fun textOnlyTurnCallsTheLegacyOpenTurnExactlyOnce() {
        // Open-seam selection: a text-only turn must take the legacy
        // `openTurn` path exactly once and never the attachment-carrying open.
        val metadata = FakeMetadataStore(mutableListOf(profile()))
        val credentials = FakeCredentialStore(
            mutableMapOf(PROFILE_ID to SecretValue(FIXTURE_KEY))
        )
        val transport = AttachmentCapturingTransport()

        val events = collect(
            repository(metadata, credentials, transport)
        ) { it.streamTurn(PROFILE_ID, SESSION_ID, "Hallo") }

        assertEquals(
            listOf<ChatTurnEvent>(
                ChatTurnEvent.RunStarted("run_synthetic"),
                ChatTurnEvent.TurnCompleted()
            ),
            events
        )
        assertEquals(1, transport.openTurnCalls)
        assertEquals("Hallo", transport.openTurnMessage)
        assertEquals(0, transport.attachmentCalls)
        assertTrue(transport.attachmentMessage == null)
    }

    @Test
    fun imageTurnCallsTheAttachmentOpenExactlyOnceWithTheImageList() {
        // Open-seam selection: a turn with an inline image must take the
        // attachment-carrying open exactly once with the validated body
        // (trimmed text plus the data URL list) and never the legacy open.
        val metadata = FakeMetadataStore(mutableListOf(profile()))
        val credentials = FakeCredentialStore(
            mutableMapOf(PROFILE_ID to SecretValue(FIXTURE_KEY))
        )
        val transport = AttachmentCapturingTransport()

        val events = collect(
            repository(metadata, credentials, transport)
        ) {
            it.streamTurnWithAttachments(
                PROFILE_ID, SESSION_ID, "  Hallo  ", listOf(IMAGE_DATA_URL)
            )
        }

        assertEquals(
            listOf<ChatTurnEvent>(
                ChatTurnEvent.RunStarted("run_synthetic"),
                ChatTurnEvent.TurnCompleted()
            ),
            events
        )
        assertEquals(0, transport.openTurnCalls)
        assertEquals(1, transport.attachmentCalls)
        val message = checkNotNull(transport.attachmentMessage) {
            "the attachment-carrying open must receive the outbound body"
        }
        assertEquals("Hallo", message.text)
        assertEquals(listOf(IMAGE_DATA_URL), message.imageDataUrls)
    }

    @Test
    fun imageTurnTransientFailureReattachesViaResumeAndNeverRepostsTheAttachment() {
        // Re-attach boundary: the attachment travels only in the initial
        // POST body. A transient failure after the run is confirmed is
        // repaired by a body-less re-attach GET to the same run, so the
        // attachment-carrying open happens exactly once and no second chat
        // POST is ever sent.
        val metadata = FakeMetadataStore(mutableListOf(profile()))
        val credentials = FakeCredentialStore(
            mutableMapOf(PROFILE_ID to SecretValue(FIXTURE_KEY))
        )
        val reattach = ReattachTransport(
            firstAttempt = { listener ->
                listener.onEvent(ChatTurnEvent.RunStarted("run_p5"))
                listener.onEvent(ChatTurnEvent.TurnFailed(ChatStreamError.Network))
                listener.onFinished()
                HandleWithSnapshot(snapshot(runId = "run_p5", lastSeq = 2L))
            },
            resumeAttempt = { listener, _ ->
                listener.onEvent(ChatTurnEvent.AssistantDelta("fortsetzung"))
                listener.onEvent(ChatTurnEvent.TurnCompleted())
                listener.onFinished()
                HandleWithSnapshot(snapshot(runId = "run_p5", lastSeq = 3L, runCompleted = true))
            }
        )
        val transport = AttachmentReattachTransport(reattach)
        val repository = repository(
            metadata, credentials, transport,
            reconnectBackoffMs = longArrayOf(1L)
        )

        val events = collect(repository) {
            it.streamTurnWithAttachments(
                PROFILE_ID, SESSION_ID, "Hallo", listOf(IMAGE_DATA_URL)
            )
        }

        assertEquals(
            listOf<ChatTurnEvent>(
                ChatTurnEvent.RunStarted("run_p5"),
                ChatTurnEvent.StreamReconnecting,
                ChatTurnEvent.AssistantDelta("fortsetzung"),
                ChatTurnEvent.TurnCompleted()
            ),
            events
        )
        // Exactly one attachment-carrying open (the initial POST) across
        // the whole reconnect scenario; the re-attach is a body-less GET
        // to the same run, and the legacy open is never used.
        assertEquals(1, transport.openTurnWithAttachmentsCalls)
        assertEquals(0, transport.openTurnCalls)
        assertEquals(
            listOf(IMAGE_DATA_URL),
            transport.attachmentMessage?.imageDataUrls
        )
        assertEquals(1, reattach.openResumeCalls)
        assertEquals("run_p5", reattach.lastRunId)
        assertEquals(2L, reattach.lastResume?.lastSeq)
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
                interactiveApprovals: Boolean,
                listener: ChatStreamListener
            ): ChatStreamHandle {
                listener.onEvent(ChatTurnEvent.TurnCompleted())
                listener.onFinished()
                return NoopHandle
            }
        },
        capabilitySource: RunApprovalCapabilitySource = RunApprovalCapabilitySource { false },
        maxReconnectAttempts: Int = 5,
        reconnectBackoffMs: LongArray = longArrayOf(1L)
    ): StreamingChatRepository {
        val profileRepository =
            ServerProfileRepository(metadata, credentials, idFactory = { "unused-id" })
        return StreamingChatRepository(
            profileRepository,
            credentials,
            transport,
            capabilitySource,
            maxReconnectAttempts,
            reconnectBackoffMs
        )
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
        var interactiveApprovals: Boolean? = null

        override fun openTurn(
            baseUrl: String,
            apiKey: SecretValue,
            sessionId: String,
            message: String,
            interactiveApprovals: Boolean,
            listener: ChatStreamListener
        ): ChatStreamHandle {
            calls += 1
            this.baseUrl = baseUrl
            this.apiKey = apiKey
            this.sessionId = sessionId
            this.message = message
            this.interactiveApprovals = interactiveApprovals
            listener.onEvent(ChatTurnEvent.RunStarted("run_synthetic"))
            listener.onEvent(ChatTurnEvent.TurnCompleted())
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
            interactiveApprovals: Boolean,
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
            interactiveApprovals: Boolean,
            listener: ChatStreamListener
        ): ChatStreamHandle {
            opened.countDown()
            return handle
        }
    }


    /** Synthetic re-attach transport: scripted first and resume attempts. */
    private class ReattachTransport(
        private val firstAttempt: (ChatStreamListener) -> ChatStreamHandle,
        private val resumeAttempt: (ChatStreamListener, ChatStreamResumeState) -> ChatStreamHandle
    ) : ChatStreamTransport {
        var openTurnCalls = 0
        var openResumeCalls = 0
        var lastRunId: String? = null
        var lastResume: ChatStreamResumeState? = null

        override fun openTurn(
            baseUrl: String,
            apiKey: SecretValue,
            sessionId: String,
            message: String,
            interactiveApprovals: Boolean,
            listener: ChatStreamListener
        ): ChatStreamHandle {
            openTurnCalls += 1
            return firstAttempt(listener)
        }

        override fun openResume(
            baseUrl: String,
            apiKey: SecretValue,
            sessionId: String,
            runId: String,
            resume: ChatStreamResumeState,
            interactiveApprovals: Boolean,
            listener: ChatStreamListener
        ): ChatStreamHandle {
            openResumeCalls += 1
            lastRunId = runId
            lastResume = resume
            return resumeAttempt(listener, resume)
        }
    }

    /** Capturing transport: records which open seam the repository used. */
    private class AttachmentCapturingTransport : ChatStreamTransport {
        var openTurnCalls = 0
        var openTurnMessage: String? = null
        var attachmentCalls = 0
        var attachmentMessage: ChatOutboundMessage? = null

        override fun openTurn(
            baseUrl: String,
            apiKey: SecretValue,
            sessionId: String,
            message: String,
            interactiveApprovals: Boolean,
            listener: ChatStreamListener
        ): ChatStreamHandle {
            openTurnCalls += 1
            openTurnMessage = message
            listener.onEvent(ChatTurnEvent.RunStarted("run_synthetic"))
            listener.onEvent(ChatTurnEvent.TurnCompleted())
            listener.onFinished()
            return NoopHandle
        }

        override fun openTurnWithAttachments(
            baseUrl: String,
            apiKey: SecretValue,
            sessionId: String,
            message: ChatOutboundMessage,
            interactiveApprovals: Boolean,
            listener: ChatStreamListener
        ): ChatStreamHandle {
            attachmentCalls += 1
            attachmentMessage = message
            listener.onEvent(ChatTurnEvent.RunStarted("run_synthetic"))
            listener.onEvent(ChatTurnEvent.TurnCompleted())
            listener.onFinished()
            return NoopHandle
        }
    }

    /**
     * Re-attach boundary: a [ReattachTransport] that additionally records
     * every attachment-carrying open, so a test can prove the attachment
     * travels only in the initial POST and never in a re-attach.
     */
    private class AttachmentReattachTransport(
        private val inner: ReattachTransport
    ) : ChatStreamTransport {
        var openTurnCalls = 0
        var openTurnWithAttachmentsCalls = 0
        var attachmentMessage: ChatOutboundMessage? = null

        override fun openTurn(
            baseUrl: String,
            apiKey: SecretValue,
            sessionId: String,
            message: String,
            interactiveApprovals: Boolean,
            listener: ChatStreamListener
        ): ChatStreamHandle {
            openTurnCalls += 1
            return inner.openTurn(
                baseUrl = baseUrl,
                apiKey = apiKey,
                sessionId = sessionId,
                message = message,
                interactiveApprovals = interactiveApprovals,
                listener = listener
            )
        }

        override fun openTurnWithAttachments(
            baseUrl: String,
            apiKey: SecretValue,
            sessionId: String,
            message: ChatOutboundMessage,
            interactiveApprovals: Boolean,
            listener: ChatStreamListener
        ): ChatStreamHandle {
            openTurnWithAttachmentsCalls += 1
            attachmentMessage = message
            return inner.openTurnWithAttachments(
                baseUrl = baseUrl,
                apiKey = apiKey,
                sessionId = sessionId,
                message = message,
                interactiveApprovals = interactiveApprovals,
                listener = listener
            )
        }

        override fun openResume(
            baseUrl: String,
            apiKey: SecretValue,
            sessionId: String,
            runId: String,
            resume: ChatStreamResumeState,
            interactiveApprovals: Boolean,
            listener: ChatStreamListener
        ): ChatStreamHandle = inner.openResume(
            baseUrl = baseUrl,
            apiKey = apiKey,
            sessionId = sessionId,
            runId = runId,
            resume = resume,
            interactiveApprovals = interactiveApprovals,
            listener = listener
        )
    }


    private class HandleWithSnapshot(private val state: ChatStreamResumeState?) :
        ChatStreamHandle {
        override fun close() {
            // nothing to cancel
        }

        override fun resumeState(): ChatStreamResumeState? = state
    }

    private fun snapshot(
        runId: String = "run_p4",
        lastSeq: Long = 1L,
        sawError: Boolean = false,
        runCompleted: Boolean = false
    ): ChatStreamResumeState = ChatStreamResumeState(
        runId = runId,
        lastSeq = lastSeq,
        messageStarted = false,
        messageId = null,
        composed = "",
        assistantCompleted = false,
        finalText = null,
        runCompleted = runCompleted,
        sawError = sawError
    )

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
        const val IMAGE_DATA_URL = "data:image/png;base64,AA=="
        const val BASE_URL = "https://example.invalid/p/android"
        const val FIXTURE_KEY = "fixture-credential"
    }
}
