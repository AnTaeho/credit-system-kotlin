package com.example.credit_system_kotlin.observability

import com.example.credit_system_kotlin.job.event.ExternalGenerationCalled
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ExternalCallMetricsTest {

    private val registry = SimpleMeterRegistry()

    private lateinit var metrics: ExternalCallMetrics

    @BeforeEach
    fun setUp() {
        metrics = ExternalCallMetrics(registry)
    }

    private fun count(name: String): Double = registry.get(name).counter().count()

    /**
     * 없는 시계열과 0인 시계열은 Prometheus 의 rate()/increase() 에서 다르게 다뤄진다.
     * 중복 호출은 평소 0이어야 정상이므로, 미리 깔려 있지 않으면 지표 자체가 사라진다.
     */
    @Test
    fun `생성 직후 두 카운터가 0으로 등록돼 있다`() {
        assertThat(count(ExternalCallMetrics.CALLS_METRIC)).isZero()
        assertThat(count(ExternalCallMetrics.DUPLICATE_CALLS_METRIC)).isZero()
    }

    @Test
    fun `첫 시도는 호출만 세고 중복으로는 세지 않는다`() {
        metrics.onExternalGenerationCalled(ExternalGenerationCalled(1L, 0))

        assertThat(count(ExternalCallMetrics.CALLS_METRIC)).isEqualTo(1.0)
        assertThat(count(ExternalCallMetrics.DUPLICATE_CALLS_METRIC)).isZero()
    }

    @Test
    fun `두 번째 이상의 시도는 중복 외부 호출로도 센다`() {
        metrics.onExternalGenerationCalled(ExternalGenerationCalled(1L, 0))
        metrics.onExternalGenerationCalled(ExternalGenerationCalled(1L, 1))
        metrics.onExternalGenerationCalled(ExternalGenerationCalled(1L, 2))

        assertThat(count(ExternalCallMetrics.CALLS_METRIC)).isEqualTo(3.0)
        assertThat(count(ExternalCallMetrics.DUPLICATE_CALLS_METRIC)).isEqualTo(2.0)
    }

    /**
     * 카운터에 태그가 없다는 것 자체가 설계다. 태그가 붙으면
     * [MetricsCardinalityConfig] 의 태그 값 상한 계산(point/outcome/detector)이 흔들린다.
     */
    @Test
    fun `두 카운터에는 태그가 없다`() {
        val names = listOf(ExternalCallMetrics.CALLS_METRIC, ExternalCallMetrics.DUPLICATE_CALLS_METRIC)

        for (name in names) {
            assertThat(registry.get(name).counter().id.tags)
                .describedAs("%s 의 태그", name)
                .isEmpty()
        }
    }
}
