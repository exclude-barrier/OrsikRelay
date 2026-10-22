package io.github.excludebarrier.orsikrelay.domain.host

import io.github.excludebarrier.orsikrelay.domain.profile.SecretValue

/**
 * One read-only snapshot of the server host from the bridge
 * (`GET /v1/host/metrics`). Every value is optional: a source the host
 * cannot read is "no value", never an error.
 */
data class HostMetrics(
    val cpuPercent: Double?,
    val cores: Int?,
    val memoryUsedBytes: Long?,
    val memoryTotalBytes: Long?,
    /** `null` when the host has no (NVIDIA) GPU. */
    val gpu: GpuMetrics?,
    val services: List<ServiceState>
)

data class GpuMetrics(val name: String, val utilizationPercent: Int, val memoryUsedBytes: Long, val memoryTotalBytes: Long)

data class ServiceState(val name: String, val active: Boolean)

sealed interface HostMetricsResult {
    /** The server does not advertise `host_metrics` (an older bridge). */
    data object Unsupported : HostMetricsResult
    data class Ready(val metrics: HostMetrics) : HostMetricsResult
    /** The read failed (network, TLS, auth, unexpected answer). */
    data object Failed : HostMetricsResult
}

/** UI seam for the Home card; runs on `Dispatchers.IO`, idempotent reads only. */
fun interface HostMetricsSource {
    fun read(profileId: String): HostMetricsResult
}

/** Authenticated transport; production: `HermesHostMetricsClient`. */
interface HostMetricsTransport {
    fun supports(baseUrl: String, apiKey: SecretValue): Boolean
    fun read(baseUrl: String, apiKey: SecretValue): HostMetricsResult
}
