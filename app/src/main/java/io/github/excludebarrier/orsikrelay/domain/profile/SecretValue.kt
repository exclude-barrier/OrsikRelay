package io.github.excludebarrier.orsikrelay.domain.profile

/**
 * In-memory holder for the API key.
 *
 * Deliberately not a data class: the raw value must never appear in
 * generated string representations, equality output, or hash output.
 */
class SecretValue(private val raw: String) {

    fun isEmpty(): Boolean = raw.isEmpty()

    /**
     * Gives module-internal code scoped access without exposing a raw-value
     * property or returning the value directly.
     */
    internal fun <T> useRaw(block: (String) -> T): T = block(raw)

    override fun toString(): String = "[REDACTED]"
}
