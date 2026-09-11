package io.github.excludebarrier.orsikrelay.domain.run

/**
 * Inert [RunApprovalRequester] default (Mission 014).
 *
 * Used only as the fail-closed default for the chat screen's approval seam
 * when a caller does not supply one (e.g. the existing Compose chat tests
 * that do not exercise approvals, or any host that has not wired the
 * production repository). It never sends a request and never records any
 * approval content; it answers a closed [RunApprovalError.Network] failure.
 *
 * In production the [ApprovalUi] gate additionally keeps [ApprovalRequest]
 * null, so this default is never invoked for a real decision. It exists so
 * the approval seam can be a defaulted parameter without the UI ever
 * reaching a live transport by accident.
 */
object NoOpRunApprovalRequester : RunApprovalRequester {
    override suspend fun respond(
        profileId: String,
        runId: String,
        requestId: String,
        choice: RunApprovalChoice
    ): RunApprovalResult = RunApprovalResult.Failure(RunApprovalError.Network)
}
