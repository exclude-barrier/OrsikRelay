package io.github.excludebarrier.orsikrelay.domain.run

/**
 * The two approval decisions the thin client may make.
 *
 * The closed set is derived read-only from the installed server code
 * (`gateway/platforms/api_server_runs.py`, `_handle_run_approval`):
 * - a non-room-scoped request accepts
 *   `{"once","session","always","deny"}`;
 * - a room-scoped request (a one-time room grant) accepts only
 *   `{"once","deny"}` and additionally REQUIRES the exact `request_id`.
 *
 * The client deliberately exposes only the least-privilege, always-
 * permitted pair: a conscious single-operation allow (`ONCE`, wire
 * `once`) and an explicit deny (`DENY`, wire `deny`). `session` and
 * `always` widen the grant and are never offered, so the app can only
 * ever approve a single operation at a time or deny it. Both wire
 * values are present in every allowed set, so the mapping is valid
 * regardless of how the server scopes the grant.
 */
enum class RunApprovalChoice(val wireValue: String) {
    /** Approve exactly this one operation (`once`). */
    ONCE("once"),
    /** Deny the operation. */
    DENY("deny")
}
