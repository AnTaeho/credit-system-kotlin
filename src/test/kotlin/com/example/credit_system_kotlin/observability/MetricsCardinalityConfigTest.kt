package com.example.credit_system_kotlin.observability

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles

/**
 * 레지스트리 수준 카디널리티 가드의 단위 테스트.
 *
 * [SimpleMeterRegistry] 에 필터만 붙여서 Spring 컨텍스트 없이 검증한다. 필터가 실제로
 * 배선되는지는 아래 [MetricsCardinalityWiringTest] 가 본다.
 */
class MetricsCardinalityConfigTest {

    private val config = MetricsCardinalityConfig()

    private fun registryWithIdentifierGuard(): MeterRegistry =
        SimpleMeterRegistry().apply { config().meterFilter(config.denyIdentifierTagsMeterFilter()) }

    @Test
    fun `organizationId 태그가 붙은 미터는 등록되지 않는다`() {
        val registry = registryWithIdentifierGuard()

        Counter.builder("credit.custom").tag("organizationId", "1").register(registry)

        assertThat(registry.find("credit.custom").counter()).isNull()
    }

    @Test
    fun `거부된 미터에 increment 를 해도 예외가 나지 않는다`() {
        val registry = registryWithIdentifierGuard()

        // 거부된 미터로는 Micrometer 가 noop 미터를 돌려준다. 관측 가드가 도메인 호출을
        // 죽이면 안 되므로, 이 단언이 가드 설계의 핵심이다.
        val counter = Counter.builder("credit.custom").tag("jobId", "42").register(registry)

        assertThatCode { counter.increment() }.doesNotThrowAnyException()
        assertThat(counter.count()).isZero()
    }

    @Test
    fun `금지된 식별자 태그 키 전부가 거부된다`() {
        val registry = registryWithIdentifierGuard()

        for ((index, key) in MetricsCardinalityConfig.FORBIDDEN_TAG_KEYS.withIndex()) {
            Counter.builder("credit.probe.$index").tag(key, "v").register(registry)
        }

        assertThat(registry.meters).isEmpty()
    }

    @Test
    fun `식별자가 아닌 태그는 그대로 통과한다`() {
        val registry = registryWithIdentifierGuard()

        Counter.builder("credit.defense").tag("point", "confirm").tag("outcome", "stale").register(registry)

        assertThat(registry.find("credit.defense").counter()).isNotNull()
    }

    @Test
    fun `credit 접두 미터의 point 태그 값은 32개까지만 허용된다`() {
        val registry = SimpleMeterRegistry().apply {
            config().meterFilter(config.creditPointTagLimitMeterFilter())
        }
        val limit = MetricsCardinalityConfig.MAX_TAG_VALUES

        repeat(limit) { i -> Counter.builder("credit.probe").tag("point", "p$i").register(registry) }
        val accepted = registry.find("credit.probe").counters().size

        Counter.builder("credit.probe").tag("point", "p$limit").register(registry)

        assertThat(accepted).isEqualTo(limit)
        assertThat(registry.find("credit.probe").counters()).hasSize(limit)
    }
}

/**
 * 필터가 실제 애플리케이션 컨텍스트에 배선되는지, 그리고 **정상 지표를 잡아먹지 않는지** 확인한다.
 *
 * 가드가 너무 세면 2단계가 사전 등록한 14개 방어 조합이 사라진다. 그러면 알람 규칙과 대시보드가
 * 통째로 무너지는데 아무 에러도 안 난다 — 그래서 이 단언이 필요하다.
 */
@ActiveProfiles("test")
@SpringBootTest
class MetricsCardinalityWiringTest @Autowired constructor(
    private val registry: MeterRegistry,
    private val config: MetricsCardinalityConfig
) {

    @Test
    fun `카디널리티 가드가 빈으로 등록되어 있다`() {
        assertThat(config.denyIdentifierTagsMeterFilter()).isNotNull()
    }

    @Test
    fun `가드가 걸린 실제 레지스트리에서도 방어 카운터 14개 조합이 그대로 보인다`() {
        val expected = DefenseMetrics.VALID_COMBINATIONS.values.sumOf { outcomes -> outcomes.size }

        assertThat(registry.find(DefenseMetrics.DEFENSE_METRIC).counters()).hasSize(expected)
        assertThat(expected).isEqualTo(14)
    }

    @Test
    fun `실제 레지스트리에 organizationId 태그 미터를 등록하려 하면 거부된다`() {
        Counter.builder("credit.wiring.probe").tag("organizationId", "1").register(registry)

        assertThat(registry.find("credit.wiring.probe").counter()).isNull()
    }
}
