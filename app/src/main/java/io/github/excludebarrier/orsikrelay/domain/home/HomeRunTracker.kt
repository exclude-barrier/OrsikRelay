package io.github.excludebarrier.orsikrelay.domain.home

import io.github.excludebarrier.orsikrelay.domain.run.RunServerStatus
import io.github.excludebarrier.orsikrelay.domain.run.RunSessionController
import io.github.excludebarrier.orsikrelay.domain.run.RunStatusRequester
import io.github.excludebarrier.orsikrelay.domain.run.RunStatusResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * One run this app started, as the Home screen shows it.
 *
 * [awaitingApproval] is `true` while the run waits for the user's decision
 * (live approval request or server status). [uncertain] marks a run whose
 * transport was lost while the server run may still be alive.
 */
data class TrackedRun(
    val profileId: String,
    val sessionId: String,
    val runId: String?,
    val awaitingApproval: Boolean,
    val uncertain: Boolean
)

/**
 * Projects the single active run of [RunSessionController] into the Home
 * screen's run list and reconciles it with the server status.
 *
 * Only runs this app started are visible: the controller owns exactly one
 * slot and no channel exists for runs started elsewhere, so runs launched
 * by Telegram or Cron never appear here.
 *
 * The flow is cold; status polling runs only while it is collected. A run
 * that the server reports as terminal disappears from the list and is no
 * longer polled. Failed status reads are swallowed and retried on the next
 * interval; the controller itself is never modified.
 */
class HomeRunTracker(
    private val activeRun: StateFlow<RunSessionController.ActiveRun?>,
    private val statusRequester: RunStatusRequester,
    private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS
) {
    /** Cold flow; polling runs only while it is collected. */
    fun runs(): Flow<List<TrackedRun>> = channelFlow {
        var identity: RunIdentity? = null
        var serverAwaitingApproval = false
        var terminal = false
        var pollJob: Job? = null

        fun snapshot(): List<TrackedRun> {
            val active = activeRun.value ?: return emptyList()
            if (terminal) return emptyList()
            return listOf(
                TrackedRun(
                    profileId = active.profileId,
                    sessionId = active.sessionId,
                    runId = active.runId,
                    awaitingApproval = serverAwaitingApproval ||
                        active.pendingApproval != null ||
                        active.status == RunSessionController.ActiveRun.Status.WAITING_FOR_APPROVAL,
                    uncertain = active.status == RunSessionController.ActiveRun.Status.UNCERTAIN
                )
            )
        }

        activeRun.collect { active ->
            val newIdentity = active?.let {
                RunIdentity(it.profileId, it.sessionId, it.runId)
            }
            if (newIdentity != identity) {
                identity = newIdentity
                serverAwaitingApproval = false
                terminal = false
                pollJob?.cancel()
                pollJob = null
            }
            send(snapshot())

            val current = active
            val runId = current?.runId
            if (current != null && runId != null && pollJob == null && !terminal) {
                val profileId = current.profileId
                pollJob = launch {
                    while (true) {
                        val result = try {
                            statusRequester.getRunStatus(profileId, runId)
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (_: Exception) {
                            null
                        }
                        when (result) {
                            is RunStatusResult.Known -> {
                                if (result.status.isTerminal) {
                                    terminal = true
                                    send(emptyList())
                                    return@launch
                                }
                                val awaiting =
                                    result.status == RunServerStatus.WAITING_FOR_APPROVAL
                                if (awaiting != serverAwaitingApproval) {
                                    serverAwaitingApproval = awaiting
                                    send(snapshot())
                                }
                            }

                            is RunStatusResult.Failure, null -> Unit
                        }
                        delay(pollIntervalMs)
                    }
                }
            }
        }
    }.distinctUntilChanged()

    private data class RunIdentity(
        val profileId: String,
        val sessionId: String,
        val runId: String?
    )

    companion object {
        const val DEFAULT_POLL_INTERVAL_MS = 5_000L
    }
}
