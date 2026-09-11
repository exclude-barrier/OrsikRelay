package io.github.excludebarrier.orsikrelay.domain.run

/**
 * Closed, UI-safe model of one pending run approval (Mission 014).
 *
 * This is the ONLY shape the UI may render. It carries exactly the
 * fields that (a) are evidenced in the installed server contract and
 * (b) are harmless to display. Nothing else from the wire event may be
 * modelled or shown.
 *
 * Contract (derived read-only from the installed server code,
 * `gateway/platforms/api_server_runs.py` `_make_approval_notify` and
 * `tools/approval.py` / `tools/approval_gateway_wait.py`):
 * - the server emits one `approval.request` event per pending approval;
 * - `command` and `description` are REDACTED by the server before the
 *   event leaves the process (`redact_sensitive_text` /
 *   `_redact_approval_command`), so they are the only command/description
 *   text a client ever sees;
 * - `request_id` is a server-generated UUID hex that identifies exactly
 *   this pending request; the server resolves a response by it when it
 *   is sent. It is an identifier, not a secret, but it is never shown.
 * - `smart_denied` is a server risk flag: when true the guardian model
 *   denied the operation and the user is being asked to consciously
 *   override — the UI must surface a risk note.
 *
 * Deliberately NOT modelled (never shown, never needed for the
 * response): `pattern_key`, `pattern_keys`, `allow_permanent`,
 * `allow_session`, `choices`, `timestamp`. `runId` and `requestId` are
 * carried only so the exact pending request can be resolved; they are
 * not displayed.
 */
data class ApprovalRequest(
    /** Server-confirmed run ID; required to build the response URL. */
    val runId: String,
    /** Server-confirmed exact pending request ID (UUID hex); sent in the response body. */
    val requestId: String,
    /** Redacted command text (the flagged action). Displayed as "Ziel". */
    val command: String,
    /** Redacted human-readable reason. Displayed as "Begründung". */
    val description: String,
    /** Server risk flag: when true a risk note must be shown. */
    val smartDenied: Boolean
)
