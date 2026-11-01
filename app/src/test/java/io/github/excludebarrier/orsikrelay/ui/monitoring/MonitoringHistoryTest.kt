package io.github.excludebarrier.orsikrelay.ui.monitoring

import io.github.excludebarrier.orsikrelay.domain.host.GpuMetrics
import io.github.excludebarrier.orsikrelay.domain.host.HostMetrics
import io.github.excludebarrier.orsikrelay.domain.host.ServiceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MonitoringHistoryTest {
    private fun metrics(cpu: Double? = 50.0, used: Long? = 2, total: Long? = 4, gpu: Int? = null, services: List<ServiceState> = emptyList()) =
        HostMetrics(cpu, 8, used, total, gpu?.let { GpuMetrics("RTX", it, 1, 2) }, services)

    @Test
    fun theHistoryStartsEmptyAndKeepsTheNewest24() {
        var history = MetricHistory()
        assertEquals(0, history.size)
        repeat(30) { history = history.plus(metrics(cpu = it.toDouble())) }

        assertEquals(HISTORY_SIZE, history.size)
        assertEquals(0.06f, history.cpu.first()!!, 0.0001f)
        assertEquals(0.29f, history.cpu.last()!!, 0.0001f)
    }

    @Test
    fun missingValuesStayMissingInsteadOfZero() {
        val history = MetricHistory().plus(metrics(cpu = null, used = null, gpu = null))

        assertNull(history.cpu.single())
        assertNull(history.ram.single())
        assertNull(history.gpu.single())
    }

    @Test
    fun memoryNeedsBothValuesAndAPositiveTotal() {
        assertEquals(0.5f, memoryFill(metrics(used = 2, total = 4))!!, 0.0001f)
        assertNull(memoryFill(metrics(used = 2, total = 0)))
        assertNull(memoryFill(metrics(used = null, total = 4)))
    }

    @Test
    fun freshnessCountsSecondsThenMinutes() {
        assertEquals(Freshness.Now, freshness(10_000, 13_000))
        assertEquals(Freshness.Seconds(12), freshness(10_000, 22_000))
        assertEquals(Freshness.Minutes(2), freshness(0, 150_000))
        // A clock that went backwards never shows a negative age.
        assertEquals(Freshness.Now, freshness(20_000, 10_000))
    }

    @Test
    fun onlyAConfirmedStopCountsAsStopped() {
        val m = metrics(services = listOf(ServiceState("a", true), ServiceState("b", false), ServiceState("c", null)))

        assertEquals(listOf("b"), stoppedServices(m))
    }
}
