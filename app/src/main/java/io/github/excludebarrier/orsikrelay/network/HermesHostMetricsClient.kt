package io.github.excludebarrier.orsikrelay.network

import io.github.excludebarrier.orsikrelay.domain.host.GpuMetrics
import io.github.excludebarrier.orsikrelay.domain.host.HostMetrics
import io.github.excludebarrier.orsikrelay.domain.host.HostMetricsResult
import io.github.excludebarrier.orsikrelay.domain.host.HostMetricsTransport
import io.github.excludebarrier.orsikrelay.domain.host.ServiceState
import io.github.excludebarrier.orsikrelay.domain.profile.ProfileBaseUrlValidator
import io.github.excludebarrier.orsikrelay.domain.profile.SecretValue
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Reads the bridge's `GET /v1/host/metrics` and checks `/v1/capabilities`
 * for `host_metrics`. HTTPS only, no redirects, bounded bodies; an
 * unexpected shape of one field drops only that field.
 */
class HermesHostMetricsClient(
    okHttpClient: OkHttpClient = HermesHttp.defaultClient()
) : HostMetricsTransport {

    private val client = okHttpClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    override fun supports(baseUrl: String, apiKey: SecretValue): Boolean {
        val url = base(baseUrl, apiKey)?.newBuilder()?.addPathSegment("v1")?.addPathSegment("capabilities")?.build()
            ?: return false
        return try {
            client.newCall(get(url, apiKey)).execute().use { response ->
                if (response.code != 200) return false
                requireJsonContentType(response) { return false }
                val root = JSONObject(readBoundedUtf8(response.body, MAX_BYTES) { return false })
                val endpoint = root.optJSONObject("endpoints")?.optJSONObject("host_metrics") ?: return false
                root.optJSONObject("features")?.opt("host_metrics") == true &&
                    endpoint.opt("method") == "GET" && endpoint.opt("path") == PATH
            }
        } catch (_: Exception) {
            false
        }
    }

    override fun read(baseUrl: String, apiKey: SecretValue): HostMetricsResult {
        val url = base(baseUrl, apiKey)?.newBuilder()?.addPathSegment("v1")?.addPathSegment("host")
            ?.addPathSegment("metrics")?.build() ?: return HostMetricsResult.Failed
        return try {
            client.newCall(get(url, apiKey)).execute().use { response ->
                if (response.code != 200) return HostMetricsResult.Failed
                requireJsonContentType(response) { return HostMetricsResult.Failed }
                val root = JSONObject(readBoundedUtf8(response.body, MAX_BYTES) { return HostMetricsResult.Failed })
                if (root.opt("object") != OBJECT) return HostMetricsResult.Failed
                HostMetricsResult.Ready(parse(root))
            }
        } catch (_: Exception) {
            HostMetricsResult.Failed
        }
    }

    private fun parse(root: JSONObject): HostMetrics {
        val cpu = root.optJSONObject("cpu")
        val memory = root.optJSONObject("memory")
        val gpu = root.optJSONObject("gpu")?.let { g ->
            val name = (g.opt("name") as? String)?.takeIf { isSafeServerString(it, MAX_NAME) }
            val utilization = g.opt("utilization_percent") as? Int
            val used = g.longOrNull("memory_used_bytes")
            val total = g.longOrNull("memory_total_bytes")
            if (name != null && utilization != null && used != null && total != null) {
                GpuMetrics(name, utilization.coerceIn(0, 100), used, total)
            } else {
                null
            }
        }
        val services = root.optJSONArray("services")?.let { array ->
            (0 until minOf(array.length(), MAX_SERVICES)).mapNotNull { i ->
                val item = array.optJSONObject(i) ?: return@mapNotNull null
                val name = (item.opt("name") as? String)?.takeIf { isSafeServerString(it, MAX_NAME) }
                // true/false as systemd answered, null = the bridge could not ask.
                val active = item.opt("active")
                when {
                    name == null -> null
                    active is Boolean -> ServiceState(name, active)
                    // An explicit null is "unknown"; a missing field is malformed.
                    item.has("active") && item.isNull("active") -> ServiceState(name, null)
                    else -> null
                }
            }
        }.orEmpty()
        return HostMetrics(
            cpuPercent = (cpu?.opt("percent") as? Number)?.toDouble()?.takeIf { it in 0.0..100.0 },
            cores = (cpu?.opt("cores") as? Int)?.takeIf { it > 0 },
            memoryUsedBytes = memory?.longOrNull("used_bytes"),
            memoryTotalBytes = memory?.longOrNull("total_bytes"),
            gpu = gpu,
            services = services
        )
    }

    /** Only a JSON integer within Long counts; 1e400 or a huge BigInteger is no value. */
    private fun JSONObject.longOrNull(key: String): Long? = when (val value = opt(key)) {
        is Int -> value.toLong()
        is Long -> value
        else -> null
    }?.takeIf { it >= 0 }

    private fun base(baseUrl: String, apiKey: SecretValue): HttpUrl? {
        if (!isSafeApiKey(apiKey)) return null
        return ProfileBaseUrlValidator.validate(baseUrl).getOrNull()?.toHttpUrl()
    }

    private fun get(url: HttpUrl, apiKey: SecretValue): Request =
        apiKey.useRaw { rawKey -> Request.Builder().url(url).get().header("Authorization", "Bearer $rawKey").build() }

    private companion object {
        const val MAX_BYTES = 64 * 1024
        const val PATH = "/v1/host/metrics"
        const val OBJECT = "orsik.host.metrics"
        const val MAX_NAME = 64
        const val MAX_SERVICES = 8
    }
}
