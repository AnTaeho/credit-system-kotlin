package com.example.credit_system_kotlin.observability

import com.example.credit_system_kotlin.global.event.DefenseOutcome
import com.example.credit_system_kotlin.global.event.DefensePoint
import com.example.credit_system_kotlin.global.event.DrainOutcome
import com.example.credit_system_kotlin.job.event.RecoveryDetector
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

    /**
     * 상한 주석이 적고 있는 "point 7개, outcome 10개(DefenseOutcome 8 + DrainOutcome 2),
     * detector 4개"를 실제와 맞춘다.
     * 이 프로젝트는 주석의 숫자가 실제와 어긋나는 것을 결함으로 센다(커밋 2dc5b0a).
     * enum 에 값을 더하면 여기가 먼저 깨져서 주석을 같이 고치게 만든다.
     */
    @Test
    fun `상한 주석이 세는 태그 값 개수가 실제 enum 과 같다`() {
        assertThat(DefensePoint.entries).hasSize(7)
        assertThat(DefenseOutcome.entries).hasSize(8)
        // outcome 태그를 쓰는 enum 이 하나 더 있다. 필터는 credit. 접두 전체에서 값을 세므로
        // 상한에 걸리는 것은 두 enum 의 합(10)이다.
        assertThat(DrainOutcome.entries).hasSize(2)
        assertThat(DefenseOutcome.entries.size + DrainOutcome.entries.size).isEqualTo(10)
        assertThat(RecoveryDetector.entries).hasSize(4)

        // 상한 32는 "지금의 세 배가 돼도 안 걸린다"는 뜻이다. 그 여유가 아직 있는지도 본다.
        val largest = maxOf(
            DefensePoint.entries.size,
            DefenseOutcome.entries.size + DrainOutcome.entries.size,
            RecoveryDetector.entries.size
        )
        assertThat(largest * 3).isLessThanOrEqualTo(MetricsCardinalityConfig.MAX_TAG_VALUES)
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
 * 가드가 너무 세면 사전 등록한 15개 방어 조합이 사라진다. 그러면 알람 규칙과 대시보드가
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
    fun `가드가 걸린 실제 레지스트리에서도 방어 카운터 15개 조합이 그대로 보인다`() {
        val expected = DefenseMetrics.VALID_COMBINATIONS.values.sumOf { outcomes -> outcomes.size }

        assertThat(registry.find(DefenseMetrics.DEFENSE_METRIC).counters()).hasSize(expected)
        assertThat(expected).isEqualTo(15)
    }

    @Test
    fun `실제 레지스트리에 organizationId 태그 미터를 등록하려 하면 거부된다`() {
        Counter.builder("credit.wiring.probe").tag("organizationId", "1").register(registry)

        assertThat(registry.find("credit.wiring.probe").counter()).isNull()
    }
}
