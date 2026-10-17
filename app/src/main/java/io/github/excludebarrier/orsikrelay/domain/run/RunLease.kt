package io.github.excludebarrier.orsikrelay.domain.run

/**
 * Minimal durable record of a server-confirmed run (run-lease process-death
 * recovery).
 *
 * Only the identity needed for an authoritative reconciliation is kept: the
 * owning [profileId]/[sessionId] and the confirmed [runId]. No message text,
 * assistant text, tool data, approval payload or credential is ever part of
 * a lease. After a process death the lease re-establishes the single run
 * slot as UNCERTAIN, so the run-slot invariant "never blindly POST a second
 * turn" survives the process; only the existing explicit reconciliation
 * (`GET /v1/runs/{run_id}`) may release it.
 */
data class RunLease(
    val profileId: String,
    val sessionId: String,
    val runId: String
)

/**
 * Persistence seam for the single [RunLease].
 *
 * Implementations do blocking I/O and are only called off the main thread.
 * [load] returns `null` for a missing or incomplete record; it may throw for
 * an unreadable store, which the controller treats as "no lease".
 */
interface RunLeaseStore {
    fun load(): RunLease?

    fun save(lease: RunLease)

    fun clear()
}

/** Default store without persistence (tests, previews). */
object NoOpRunLeaseStore : RunLeaseStore {
    override fun load(): RunLease? = null

    override fun save(lease: RunLease) = Unit

    override fun clear() = Unit
}
