package io.github.excludebarrier.orsikrelay.domain.run

/**
 * Closed result of one approval decision.
 *
 * Contract derived read-only from the installed server code
 * (`gateway/platforms/api_server_runs.py`, `_handle_run_approval`):
 * - [Resolved] is the server's HTTP 200 answer
 *   `{"object":"hermes.run.approval_response","run_id":<id>,
 *   "choice":<sent choice>,"resolved":<n>=1>}` for exactly the
 *   requested run: the pending approval was resolved with the sent
 *   choice.
 * - [NoPending] is the server's HTTP 409 answer
 *   (`approval_not_pending` / `approval_not_active`): there is no
 *   pending approval for the run anymore (already resolved, answered
 *   elsewhere, or timed out). This is a real, evidenced terminal
 *   outcome — the decision the user made could not be applied because
 *   the request no longer exists.
 * - [Failure] carries a closed [RunApprovalError] reason and never
 *   exposes run IDs, request IDs, response bodies, URLs, keys or
 *   exception text.
 */
sealed interface RunApprovalResult {
    /** The server resolved the pending approval with [choice]. */
    data class Resolved(val runId: String, val choice: RunApprovalChoice) : RunApprovalResult

    /** The run has no pending approval; nothing was resolved. */
    data class NoPending(val runId: String) : RunApprovalResult

    /** The decision failed; [error] is closed and generic. */
    data class Failure(val error: RunApprovalError) : RunApprovalResult
}
