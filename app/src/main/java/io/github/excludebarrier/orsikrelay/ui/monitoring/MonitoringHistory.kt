package io.github.excludebarrier.orsikrelay.ui.monitoring

import io.github.excludebarrier.orsikrelay.domain.host.HostMetrics

/** Values the monitoring page keeps per poll; kept only while the page is open. */
internal const val HISTORY_SIZE = 24

/**
 * A local ring of the last [HISTORY_SIZE] readings (0..1, null = no value),
 * newest last. It starts empty when the page opens: there is no server-side
 * history, so nothing older is ever shown.
 */
internal data class MetricHistory(val cpu: List<Float?> = emptyList(), val ram: List<Float?> = emptyList(), val gpu: List<Float?> = emptyList()) {
    fun plus(metrics: HostMetrics): MetricHistory = MetricHistory(
        cpu = (cpu + metrics.cpuPercent?.let { (it / 100).toFloat().coerceIn(0f, 1f) }).takeLast(HISTORY_SIZE),
        ram = (ram + memoryFill(metrics)).takeLast(HISTORY_SIZE),
        gpu = (gpu + metrics.gpu?.let { (it.utilizationPercent / 100f).coerceIn(0f, 1f) }).takeLast(HISTORY_SIZE)
    )

    val size: Int get() = cpu.size
}

/** Share of the memory in use, or null when the host did not report both values. */
internal fun memoryFill(metrics: HostMetrics): Float? {
    val used = metrics.memoryUsedBytes ?: return null
    val total = metrics.memoryTotalBytes?.takeIf { it > 0 } ?: return null
    return (used.toFloat() / total).coerceIn(0f, 1f)
}

/** How old the shown values are, for "Stand: vor N s". */
internal sealed interface Freshness {
    data object Now : Freshness
    data class Seconds(val value: Long) : Freshness
    data class Minutes(val value: Long) : Freshness
}

/** Below 5 s the values count as just updated. */
internal fun freshness(updatedAtMs: Long, nowMs: Long): Freshness {
    val seconds = ((nowMs - updatedAtMs).coerceAtLeast(0)) / 1_000
    return when {
        seconds < 5 -> Freshness.Now
        seconds < 60 -> Freshness.Seconds(seconds)
        else -> Freshness.Minutes(seconds / 60)
    }
}

/** Stopped services (a confirmed stop; `active == null` is unknown, not stopped). */
internal fun stoppedServices(metrics: HostMetrics): List<String> =
    metrics.services.filter { it.active == false }.map { it.name }
