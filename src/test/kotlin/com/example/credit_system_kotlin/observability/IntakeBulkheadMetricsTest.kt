package com.example.credit_system_kotlin.observability

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * 상한에 닿았는지는 이 두 값으로만 보인다. 상한이 커서 아무 일도 없었던 것과 접수가 묶여
 * 기다린 것을 구분하지 못하면 측정 결과를 해석할 수 없다.
 */
class IntakeBulkheadMetricsTest {

    private val registry = SimpleMeterRegistry()
    private val permits = Semaphore(PERMITS, true)
    private val metrics = IntakeBulkheadMetrics(registry, permits, PERMITS)

    private fun used(): Double =
        registry.get(IntakeBulkheadMetrics.PERMITS_USED_METRIC).gauge().value()

    @Test
    fun `쓰이는 허가 수를 따라간다`() {
        assertThat(used()).isZero()

        permits.acquire(2)
        assertThat(used()).isEqualTo(2.0)

        permits.release()
        assertThat(used()).isEqualTo(1.0)

        permits.release()
        assertThat(used()).isZero()
    }

    @Test
    fun `기다린 시간은 획득할 때마다 쌓인다`() {
        metrics.recordWait(TimeUnit.MILLISECONDS.toNanos(30))
        metrics.recordWait(0)

        val timer = registry.get(IntakeBulkheadMetrics.WAIT_METRIC).timer()
        assertThat(timer.count()).isEqualTo(2)
        assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isGreaterThanOrEqualTo(30.0)
    }

    @Test
    fun `두 미터에 태그를 붙이지 않는다`() {
        metrics.recordWait(0)

        assertThat(registry.get(IntakeBulkheadMetrics.PERMITS_USED_METRIC).gauge().id.tags).isEmpty()
        assertThat(registry.get(IntakeBulkheadMetrics.WAIT_METRIC).timer().id.tags).isEmpty()
    }

    companion object {
        private const val PERMITS = 3
    }
}
