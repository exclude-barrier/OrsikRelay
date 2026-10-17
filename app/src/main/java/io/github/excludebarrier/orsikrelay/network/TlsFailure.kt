package io.github.excludebarrier.orsikrelay.network

import javax.net.ssl.SSLException

/**
 * Whether this failure was caused by TLS: the throwable itself, one of its
 * causes, or one of their suppressed exceptions is an [SSLException].
 *
 * OkHttp 5 connects to several addresses of a host in parallel ("fast
 * fallback"). When every route fails it throws a plain
 * `java.net.ConnectException` and attaches the individual route failures as
 * suppressed exceptions, so an untrusted certificate on the reachable route
 * no longer surfaces as an [SSLException] at the top level. Without this check
 * a certificate problem would be reported as a generic network failure.
 *
 * The walk is bounded and cycle-safe; it never reads exception messages.
 */
internal fun Throwable.hasTlsFailure(): Boolean {
    val pending = ArrayDeque<Throwable>()
    val seen = HashSet<Throwable>()
    pending.addLast(this)
    while (pending.isNotEmpty() && seen.size < MAX_INSPECTED_THROWABLES) {
        val current = pending.removeFirst()
        if (!seen.add(current)) continue
        if (current is SSLException) return true
        current.cause?.let { pending.addLast(it) }
        current.suppressed.forEach { pending.addLast(it) }
    }
    return false
}

private const val MAX_INSPECTED_THROWABLES = 32
