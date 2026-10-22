package io.github.excludebarrier.orsikrelay.domain.home

import io.github.excludebarrier.orsikrelay.domain.run.ApprovalRequest
import io.github.excludebarrier.orsikrelay.domain.run.RunServerStatus
import io.github.excludebarrier.orsikrelay.domain.run.RunSessionController
import io.github.excludebarrier.orsikrelay.domain.run.RunStatusError
import io.github.excludebarrier.orsikrelay.domain.run.RunStatusRequester
import io.github.excludebarrier.orsikrelay.domain.run.RunStatusResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for [HomeRunTracker]: the live projection of the active
 * run, the immediate/interval status polling, the terminal hide-and-stop
 * rule, failure tolerance and the polling lifetime. All time is virtual;
 * the requester is a synthetic fake.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeRunTrackerTest {

    private val profileId = "p"
    private val sessionId = "s"
    private val runId = "run_" + "a".repeat(32)
    private val requestId = "b".repeat(32)

    private fun activeRun(
        runId: String? = this.runId,
        pendingApproval: ApprovalRequest? = null,
        uncertain: Boolean = false
    ) = RunSessionController.ActiveRun(
        profileId = profileId,
        sessionId = sessionId,
        runId = runId,
        pendingApproval = pendingApproval,
        uncertain = uncertain
    )

    private fun approvalRequest() =
        ApprovalRequest(runId, requestId, ApprovalRequest.SAFE_DESCRIPTION)

    private class FakeStatusRequester : RunStatusRequester {
        var calls = 0
            private set
        var lastProfileId: String? = null
            private set
        var lastRunId: String? = null
            private set
        var throwRuntime = false
        var result: (Int) -> RunStatusResult = {
            RunStatusResult.Known(RunServerStatus.RUNNING)
        }

        override suspend fun getRunStatus(profileId: String, runId: String): RunStatusResult {
            calls++
            lastProfileId = profileId
            lastRunId = runId
            if (throwRuntime) throw IllegalStateException("requester failed")
            return result(calls)
        }
    }

    private fun TestScope.startCollecting(
        tracker: HomeRunTracker,
        emissions: MutableList<List<TrackedRun>>
    ) = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
        tracker.runs().collect { emissions.add(it) }
    }

    @Test
    fun noActiveRun_emitsEmptyListAndMakesNoRequests() = runTest {
        val active = MutableStateFlow<RunSessionController.ActiveRun?>(null)
        val requester = FakeStatusRequester()
        val tracker = HomeRunTracker(active, requester)
        val emissions = mutableListOf<List<TrackedRun>>()

        startCollecting(tracker, emissions)
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(listOf(emptyList<TrackedRun>()), emissions)
        assertEquals(0, requester.calls)
    }

    @Test
    fun runWithoutRunId_emitsOneEntryAndMakesNoRequests() = runTest {
        val active = MutableStateFlow<RunSessionController.ActiveRun?>(activeRun(runId = null))
        val requester = FakeStatusRequester()
        val tracker = HomeRunTracker(active, requester)
        val emissions = mutableListOf<List<TrackedRun>>()

        startCollecting(tracker, emissions)
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()

        val expected = TrackedRun(
            profileId = profileId,
            sessionId = sessionId,
            runId = null,
            awaitingApproval = false,
            uncertain = false
        )
        assertEquals(listOf(expected), emissions.last())
        assertEquals(0, requester.calls)
    }

    @Test
    fun runWithRunId_pollsImmediatelyAndThenEveryInterval() = runTest {
        val active = MutableStateFlow<RunSessionController.ActiveRun?>(activeRun())
        val requester = FakeStatusRequester()
        val tracker = HomeRunTracker(active, requester, pollIntervalMs = 5_000L)
        val emissions = mutableListOf<List<TrackedRun>>()

        startCollecting(tracker, emissions)
        runCurrent()
        assertEquals(1, requester.calls)
        assertEquals(profileId, requester.lastProfileId)
        assertEquals(runId, requester.lastRunId)

        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(2, requester.calls)

        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(3, requester.calls)
    }

    @Test
    fun serverWaitingForApproval_setsAwaitingApproval() = runTest {
        val active = MutableStateFlow<RunSessionController.ActiveRun?>(activeRun())
        val requester = FakeStatusRequester().apply {
            result = { RunStatusResult.Known(RunServerStatus.WAITING_FOR_APPROVAL) }
        }
        val tracker = HomeRunTracker(active, requester)
        val emissions = mutableListOf<List<TrackedRun>>()

        startCollecting(tracker, emissions)
        runCurrent()

        assertTrue(emissions.first()[0].awaitingApproval.not())
        assertTrue(emissions.last()[0].awaitingApproval)
    }

    @Test
    fun liveApproval_showsAwaitingApprovalWithoutPolling() = runTest {
        val active = MutableStateFlow<RunSessionController.ActiveRun?>(
            activeRun(runId = null, pendingApproval = approvalRequest())
        )
        val requester = FakeStatusRequester()
        val tracker = HomeRunTracker(active, requester)
        val emissions = mutableListOf<List<TrackedRun>>()

        startCollecting(tracker, emissions)
        runCurrent()

        assertTrue(emissions.last()[0].awaitingApproval)
        assertEquals(0, requester.calls)
    }

    @Test
    fun terminalServerStatus_hidesRunAndStopsPolling() = runTest {
        val active = MutableStateFlow<RunSessionController.ActiveRun?>(activeRun())
        val requester = FakeStatusRequester().apply {
            result = { RunStatusResult.Known(RunServerStatus.COMPLETED) }
        }
        val tracker = HomeRunTracker(active, requester)
        val emissions = mutableListOf<List<TrackedRun>>()

        startCollecting(tracker, emissions)
        runCurrent()

        assertEquals(emptyList<TrackedRun>(), emissions.last())
        assertEquals(1, requester.calls)

        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, requester.calls)
        assertEquals(emptyList<TrackedRun>(), emissions.last())
    }

    @Test
    fun statusFailure_keepsLastStateAndKeepsPolling() = runTest {
        val active = MutableStateFlow<RunSessionController.ActiveRun?>(activeRun())
        val requester = FakeStatusRequester().apply {
            result = { call ->
                if (call == 1) {
                    RunStatusResult.Known(RunServerStatus.WAITING_FOR_APPROVAL)
                } else {
                    RunStatusResult.Failure(RunStatusError.Network)
                }
            }
        }
        val tracker = HomeRunTracker(active, requester)
        val emissions = mutableListOf<List<TrackedRun>>()

        startCollecting(tracker, emissions)
        runCurrent()
        assertTrue(emissions.last()[0].awaitingApproval)

        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(2, requester.calls)
        assertTrue(emissions.last()[0].awaitingApproval)

        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(3, requester.calls)
        assertTrue(emissions.last()[0].awaitingApproval)
    }

    @Test
    fun requesterException_isTreatedLikeFailure() = runTest {
        val active = MutableStateFlow<RunSessionController.ActiveRun?>(activeRun())
        val requester = FakeStatusRequester().apply { throwRuntime = true }
        val tracker = HomeRunTracker(active, requester)
        val emissions = mutableListOf<List<TrackedRun>>()

        startCollecting(tracker, emissions)
        runCurrent()
        assertEquals(1, requester.calls)
        assertEquals(1, emissions.size)

        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(2, requester.calls)
        assertEquals(profileId, requester.lastProfileId)
        assertEquals(runId, requester.lastRunId)
    }

    @Test
    fun activeRunCleared_stopsPolling() = runTest {
        val active = MutableStateFlow<RunSessionController.ActiveRun?>(activeRun())
        val requester = FakeStatusRequester()
        val tracker = HomeRunTracker(active, requester)
        val emissions = mutableListOf<List<TrackedRun>>()

        startCollecting(tracker, emissions)
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(2, requester.calls)

        active.value = null
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(2, requester.calls)
        assertEquals(emptyList<TrackedRun>(), emissions.last())
    }

    @Test
    fun uncertainRun_isMarkedUncertain() = runTest {
        val active = MutableStateFlow<RunSessionController.ActiveRun?>(
            activeRun(uncertain = true)
        )
        val requester = FakeStatusRequester()
        val tracker = HomeRunTracker(active, requester)
        val emissions = mutableListOf<List<TrackedRun>>()

        startCollecting(tracker, emissions)
        runCurrent()

        assertTrue(emissions.last()[0].uncertain)
    }

    @Test
    fun cancellingCollector_stopsPolling() = runTest {
        val active = MutableStateFlow<RunSessionController.ActiveRun?>(activeRun())
        val requester = FakeStatusRequester()
        val tracker = HomeRunTracker(active, requester)
        val emissions = mutableListOf<List<TrackedRun>>()

        val job = startCollecting(tracker, emissions)
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(2, requester.calls)

        job.cancel()
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(2, requester.calls)
        assertFalse(job.isActive)
    }

    /** Records release requests; answers like the controller (only an uncertain slot is released). */
    private class ReleaseRecorder(private val active: MutableStateFlow<RunSessionController.ActiveRun?>) {
        val released = mutableListOf<Triple<String, String, String>>()

        fun release(profileId: String, sessionId: String, runId: String): Boolean {
            val current = active.value ?: return false
            if (!current.uncertain || current.runId != runId) return false
            released += Triple(profileId, sessionId, runId)
            active.value = null
            return true
        }
    }

    @Test
    fun uncertainRunUnknownToTheServer_isReleasedAndHidden() = runTest {
        val active = MutableStateFlow<RunSessionController.ActiveRun?>(activeRun(uncertain = true))
        val requester = FakeStatusRequester().apply {
            result = { RunStatusResult.Failure(RunStatusError.NotFound) }
        }
        val recorder = ReleaseRecorder(active)
        val tracker = HomeRunTracker(active, requester, releaseUncertain = recorder::release)
        val emissions = mutableListOf<List<TrackedRun>>()

        startCollecting(tracker, emissions)
        runCurrent()

        assertEquals(listOf(Triple(profileId, sessionId, runId)), recorder.released)
        assertEquals(emptyList<TrackedRun>(), emissions.last())
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, requester.calls)
    }

    @Test
    fun liveRunUnknownToTheServer_staysAndKeepsPolling() = runTest {
        val active = MutableStateFlow<RunSessionController.ActiveRun?>(activeRun(uncertain = false))
        val requester = FakeStatusRequester().apply {
            result = { RunStatusResult.Failure(RunStatusError.NotFound) }
        }
        val recorder = ReleaseRecorder(active)
        val tracker = HomeRunTracker(active, requester, releaseUncertain = recorder::release)
        val emissions = mutableListOf<List<TrackedRun>>()

        startCollecting(tracker, emissions)
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()

        // A live stream is authoritative; a 404 alone never frees its slot.
        assertTrue(recorder.released.isEmpty())
        assertEquals(1, emissions.last().size)
        assertEquals(2, requester.calls)
    }

    @Test
    fun uncertainRunProvenTerminal_releasesTheSlot() = runTest {
        val active = MutableStateFlow<RunSessionController.ActiveRun?>(activeRun(uncertain = true))
        val requester = FakeStatusRequester().apply {
            result = { RunStatusResult.Known(RunServerStatus.COMPLETED) }
        }
        val recorder = ReleaseRecorder(active)
        val tracker = HomeRunTracker(active, requester, releaseUncertain = recorder::release)
        val emissions = mutableListOf<List<TrackedRun>>()

        startCollecting(tracker, emissions)
        runCurrent()

        assertEquals(listOf(Triple(profileId, sessionId, runId)), recorder.released)
        assertEquals(emptyList<TrackedRun>(), emissions.last())
    }
}
