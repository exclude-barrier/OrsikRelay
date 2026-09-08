package io.github.excludebarrier.orsikrelay.domain.run

/**
 * Closed result of one run-stop attempt (Mission 007A).
 *
 * Contract derived read-only from the installed server code
 * (`gateway/platforms/api_server_runs.py`, `_handle_stop_run`):
 * - [Accepted] is the server's `{"run_id": <id>, "status": "stopping"}`
 *   answer for exactly the requested run: the stop request was accepted,
 *   the run will settle terminal later. It is NOT yet stopped.
 * - [AlreadyTerminal] is the server returning the pollable status object
 *   of a run that already settled with one of the server's terminal
 *   statuses (`completed`, `failed`, `cancelled`, `interrupted` per
 *   `TERMINAL_STATUSES` in `api_server_run_idempotency.py`): the run is
 *   already terminal, so this attempt did not perform a fresh stop.
 * - [Failure] carries a closed [RunStopError] reason and never exposes
 *   run IDs, response bodies, URLs, keys or exception text.
 */
sealed interface RunStopResult {
    /** The server accepted the stop for [runId]; the run is stopping. */
    data class Accepted(val runId: String) : RunStopResult
    /** [runId] already settled with a terminal status; nothing to stop. */
    data class AlreadyTerminal(val runId: String) : RunStopResult
    /** The stop attempt failed; [error] is closed and generic. */
    data class Failure(val error: RunStopError) : RunStopResult
}
