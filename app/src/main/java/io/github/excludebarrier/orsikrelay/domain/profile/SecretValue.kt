package io.github.excludebarrier.orsikrelay.domain.profile

/**
 * In-memory holder for the API key (Mission 002A).
 *
 * Deliberately not a data class and without the default `toString()`:
 * the raw value must never appear in logs, error messages, crash
 * reports, Semantics or test output. `toString()` always returns
 * `[REDACTED]`.
 *
 * No persistence and no network transport in this ticket.
 */
class SecretValue(private val raw: String) {

    /** True when the wrapped key is empty. */
    fun isEmpty(): Boolean = raw.isEmpty()

    override fun toString(): String = "[REDACTED]"
}
