package io.github.excludebarrier.orsikrelay.domain.run

/**
 * Closed result of one run-steer attempt (Mission 008A).
 *
 * Contract verified read-only against the installed server:
 * - [Queued] is the server's
 *   `{"object":"hermes.run.steer","run_id":<id>,"accepted":true}`
 *   answer for exactly the requested run. It means the steer input was
 *   queued/accepted only: the run stays active and the steer may land
 *   at the model's next turn boundary. It is NOT a terminal state.
 * - [NotAccepting] is the server's closed HTTP 409 answer
 *   (`run_not_accepting_steer` or `steer_not_accepted`): the steer was
 *   not queued. A later UI may deliberately offer to send the text as
 *   a new message; this layer never does so automatically.
 * - [Failure] carries a closed [RunSteerError] reason and never exposes
 *   run IDs, steer text, response bodies, URLs, keys or exception text.
 */
sealed interface RunSteerResult {
    /** The server queued the steer for [runId]; the run stays active. */
    data class Queued(val runId: String) : RunSteerResult
    /** The server answered 409 for [runId]; the steer was not queued. */
    data class NotAccepting(val runId: String) : RunSteerResult
    /** The steer attempt failed; [error] is closed and generic. */
    data class Failure(val error: RunSteerError) : RunSteerResult
}
