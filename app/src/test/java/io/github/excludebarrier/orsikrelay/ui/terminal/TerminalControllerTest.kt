package io.github.excludebarrier.orsikrelay.ui.terminal

import io.github.excludebarrier.orsikrelay.domain.terminal.TerminalAvailability
import io.github.excludebarrier.orsikrelay.domain.terminal.TerminalCloseReason
import io.github.excludebarrier.orsikrelay.domain.terminal.TerminalConnection
import io.github.excludebarrier.orsikrelay.domain.terminal.TerminalEvent
import io.github.excludebarrier.orsikrelay.domain.terminal.TerminalSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the terminal screen state machine: the availability check,
 * the open/close lifecycle, output ordering, the pause/reconnect generation
 * guard and the deferred resize. All seams are fakes; no network.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TerminalControllerTest {

    private class FakeConnection : TerminalConnection {
        val sent = mutableListOf<ByteArray>()
        val resizes = mutableListOf<Pair<Int, Int>>()
        var closeCalls = 0

        override fun send(bytes: ByteArray): Boolean {
            sent += bytes
            return true
        }

        override fun resize(columns: Int, rows: Int): Boolean {
            resizes += columns to rows
            return true
        }

        override fun close() {
            closeCalls++
        }
    }

    private class FakeSource(
        var availability: TerminalAvailability = TerminalAvailability.Available,
        var openResult: TerminalConnection? = FakeConnection()
    ) : TerminalSource {
        var availabilityCalls = 0
        var openCalls = 0
        val callbacks = mutableListOf<(TerminalEvent) -> Unit>()

        override fun availability(profileId: String): TerminalAvailability {
            availabilityCalls++
            return availability
        }

        override fun open(profileId: String, onEvent: (TerminalEvent) -> Unit): TerminalConnection? {
            openCalls++
            callbacks += onEvent
            return openResult
        }
    }

    private fun TestScope.newController(
        source: FakeSource,
        received: MutableList<ByteArray>
    ): TerminalController {
        val controller = TerminalController(
            source = source,
            profileId = "p",
            scope = this,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler)
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            controller.output.collect { received += it }
        }
        return controller
    }

    @Test
    fun checkMapsAvailabilityToTheUnlockGate() = runTest {
        val cases = listOf(
            TerminalAvailability.Available to TerminalUiState.NeedsUnlock,
            TerminalAvailability.Unsupported to TerminalUiState.Unsupported,
            TerminalAvailability.Failed to TerminalUiState.CheckFailed
        )

        for ((availability, expected) in cases) {
            val source = FakeSource(availability = availability)
            val controller = newController(source, mutableListOf())

            controller.check()
            advanceUntilIdle()

            assertEquals(availability.toString(), expected, controller.state.value)
            assertEquals(1, source.availabilityCalls)
            controller.dispose()
        }
    }

    @Test
    fun connectDeliversOutputBytesInOrder() = runTest {
        val source = FakeSource()
        val received = mutableListOf<ByteArray>()
        val controller = newController(source, received)

        controller.connect()
        advanceUntilIdle()

        // Connecting until the socket reports it is open.
        assertEquals(TerminalUiState.Connecting, controller.state.value)
        assertEquals(1, source.openCalls)
        val onEvent = source.callbacks.single()
        onEvent(TerminalEvent.Opened)
        advanceUntilIdle()
        assertEquals(TerminalUiState.Connected, controller.state.value)
        onEvent(TerminalEvent.Output(byteArrayOf(1)))
        onEvent(TerminalEvent.Output(byteArrayOf(2, 3)))
        advanceUntilIdle()

        assertEquals(2, received.size)
        assertArrayEquals(byteArrayOf(1), received[0])
        assertArrayEquals(byteArrayOf(2, 3), received[1])

        controller.dispose()
    }

    @Test
    fun cancelWhileConnectingClosesAndIgnoresTheLateOpen() = runTest {
        val connection = FakeConnection()
        val source = FakeSource(openResult = connection)
        val controller = newController(source, mutableListOf())
        controller.connect()
        advanceUntilIdle()
        controller.cancel()
        source.callbacks.single()(TerminalEvent.Opened)
        advanceUntilIdle()
        assertEquals(TerminalUiState.Closed(TerminalCloseReason.Disconnected), controller.state.value)
        assertEquals(1, connection.closeCalls)
    }

    @Test
    fun aLongPasteIsSentInPiecesBelowTheBridgeBound() = runTest {
        val connection = FakeConnection()
        val controller = newController(FakeSource(openResult = connection), mutableListOf())
        controller.connect()
        advanceUntilIdle()
        val paste = ByteArray(100_000) { (it % 251).toByte() }

        controller.input(paste)

        val chunk = TerminalController.INPUT_CHUNK_BYTES
        assertTrue(connection.sent.all { it.size <= chunk })
        assertEquals((paste.size + chunk - 1) / chunk, connection.sent.size)
        assertArrayEquals(paste, connection.sent.fold(ByteArray(0)) { all, part -> all + part })
        controller.dispose()
    }

    @Test
    fun aClosedEventBecomesTheClosedStateWithItsReason() = runTest {
        val source = FakeSource()
        val received = mutableListOf<ByteArray>()
        val controller = newController(source, received)
        controller.connect()
        advanceUntilIdle()

        source.callbacks.single()(TerminalEvent.Closed(TerminalCloseReason.Exited))
        advanceUntilIdle()

        assertEquals(TerminalUiState.Closed(TerminalCloseReason.Exited), controller.state.value)
        assertTrue(received.isEmpty())

        controller.dispose()
    }

    @Test
    fun pauseClosesTheConnectionAndPauses() = runTest {
        val source = FakeSource()
        val connection = FakeConnection()
        source.openResult = connection
        val received = mutableListOf<ByteArray>()
        val controller = newController(source, received)
        controller.connect()
        advanceUntilIdle()

        controller.pause()
        advanceUntilIdle()

        assertEquals(TerminalUiState.Paused, controller.state.value)
        assertEquals(1, connection.closeCalls)

        // A late frame from the closed connection must not reach the screen.
        source.callbacks.single()(TerminalEvent.Output(byteArrayOf(9)))
        advanceUntilIdle()
        assertTrue(received.isEmpty())

        controller.dispose()
    }

    @Test
    fun eventsOfAnOldConnectionAreIgnoredAfterReconnect() = runTest {
        val source = FakeSource()
        val firstConnection = FakeConnection()
        val secondConnection = FakeConnection()
        source.openResult = firstConnection
        val received = mutableListOf<ByteArray>()
        val controller = newController(source, received)

        controller.connect()
        advanceUntilIdle()
        val firstVisit = source.callbacks[0]

        controller.pause()
        source.openResult = secondConnection
        controller.connect()
        advanceUntilIdle()
        val secondVisit = source.callbacks[1]

        firstVisit(TerminalEvent.Output(byteArrayOf(1)))
        firstVisit(TerminalEvent.Closed(TerminalCloseReason.Exited))
        advanceUntilIdle()

        // The old visit's events change nothing; the new one is still connecting.
        assertEquals(TerminalUiState.Connecting, controller.state.value)
        assertTrue("old output must be ignored", received.isEmpty())
        assertEquals("old close must be ignored", 0, secondConnection.closeCalls)

        secondVisit(TerminalEvent.Output(byteArrayOf(7)))
        advanceUntilIdle()
        assertEquals(1, received.size)
        assertArrayEquals(byteArrayOf(7), received[0])

        secondVisit(TerminalEvent.Closed(TerminalCloseReason.Busy))
        advanceUntilIdle()
        assertEquals(TerminalUiState.Closed(TerminalCloseReason.Busy), controller.state.value)

        controller.dispose()
    }

    @Test
    fun aResizeBeforeTheConnectionOpensIsSentOnceWhenItOpens() = runTest {
        val source = FakeSource()
        val connection = FakeConnection()
        source.openResult = connection
        val controller = newController(source, mutableListOf())

        controller.resize(100, 40)
        assertTrue("no connection yet", connection.resizes.isEmpty())

        controller.connect()
        advanceUntilIdle()

        assertEquals(listOf(100 to 40), connection.resizes)

        controller.resize(120, 50)
        assertEquals(listOf(100 to 40, 120 to 50), connection.resizes)

        controller.dispose()
    }

    @Test
    fun aNullConnectionBecomesADisconnect() = runTest {
        val source = FakeSource(openResult = null)
        val controller = newController(source, mutableListOf())

        controller.connect()
        advanceUntilIdle()

        assertEquals(TerminalUiState.Closed(TerminalCloseReason.Disconnected), controller.state.value)

        controller.dispose()
    }

    @Test
    fun disposeClosesTheConnection() = runTest {
        val source = FakeSource()
        val connection = FakeConnection()
        source.openResult = connection
        val controller = newController(source, mutableListOf())
        controller.connect()
        advanceUntilIdle()

        controller.dispose()
        advanceUntilIdle()

        assertEquals(1, connection.closeCalls)
        // Nothing can be sent through a disposed connection any more.
        controller.input(byteArrayOf(1))
        assertTrue(connection.sent.isEmpty())
    }
}
