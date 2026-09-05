package com.example.credit_system_kotlin.observability

import com.example.credit_system_kotlin.global.event.DefenseOutcome
import com.example.credit_system_kotlin.global.event.DefensePoint
import com.example.credit_system_kotlin.global.event.DefenseTriggered
import com.example.credit_system_kotlin.job.event.JobRecovered
import com.example.credit_system_kotlin.job.event.RecoveryDetector
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class DefenseMetricsTest {

    private val registry = SimpleMeterRegistry()

    private lateinit var metrics: DefenseMetrics

    @BeforeEach
    fun setUp() {
        metrics = DefenseMetrics(registry)
    }

    private fun defenseCount(point: String, outcome: String): Double =
        registry.get(DefenseMetrics.DEFENSE_METRIC)
            .tag("point", point)
            .tag("outcome", outcome)
            .counter()
            .count()

    private fun recoveryCount(detector: String): Double =
        registry.get(DefenseMetrics.RECOVERY_METRIC)
            .tag("detector", detector)
            .counter()
            .count()

    @Test
    fun `방어 이벤트는 해당 태그 조합만 올리고 나머지는 0으로 둔다`() {
        metrics.onDefenseTriggered(DefenseTriggered(DefensePoint.CONFIRM, DefenseOutcome.STALE))

        assertThat(defenseCount("confirm", "stale")).isEqualTo(1.0)
        assertThat(defenseCount("confirm", "applied")).isZero()
        assertThat(defenseCount("hold_balance", "rejected")).isZero()
    }

    @Test
    fun `회수 이벤트는 detector 태그 카운터를 올린다`() {
        metrics.onJobRecovered(JobRecovered(1L, 0, RecoveryDetector.BACKSTOP))

        assertThat(recoveryCount("backstop")).isEqualTo(1.0)
        assertThat(recoveryCount("heartbeat")).isZero()
        assertThat(recoveryCount("backstop_blind")).isZero()
    }

    /**
     * Prometheus 의 rate()/increase() 는 "없는 시계열"과 "0인 시계열"을 다르게 다룬다.
     * 한 번도 발생하지 않은 조합이 스크레이프에서 통째로 사라지면 알람 규칙이 성립하지 않으므로,
     * 생성 시점에 유효 조합 전부가 0으로 깔려 있어야 한다.
     */
    @Test
    fun `생성 직후 모든 유효 조합이 0으로 미리 등록돼 있다`() {
        val expected = mapOf(
            "hold_balance" to listOf("applied", "rejected"),
            "idem_key" to listOf("app_hit", "db_unique"),
            "worker_claim" to listOf("applied", "lost"),
            "confirm" to listOf("applied", "stale"),
            "mark_failed" to listOf("applied", "stale"),
            "retry_claim" to listOf("applied", "lost"),
            "final_refund" to listOf("applied", "raced")
        )

        for ((point, outcomes) in expected) {
            for (outcome in outcomes) {
                assertThat(defenseCount(point, outcome))
                    .describedAs("%s/%s 가 이벤트 없이도 등록돼 있어야 한다", point, outcome)
                    .isZero()
            }
        }
        assertThat(recoveryCount("heartbeat")).isZero()
        assertThat(recoveryCount("backstop")).isZero()
        assertThat(recoveryCount("backstop_blind"))
            .describedAs("backstop_blind 도 RecoveryDetector.entries 를 따라 0으로 깔려 있어야 한다")
            .isZero()

        val registeredCombinations = registry.get(DefenseMetrics.DEFENSE_METRIC).counters().size
        assertThat(registeredCombinations).isEqualTo(expected.values.sumOf { it.size })
    }

    @Test
    fun `태그 값은 enum 이름을 소문자로 바꾼 것이다`() {
        val tagValues = registry.get(DefenseMetrics.DEFENSE_METRIC).counters()
            .flatMap { it.id.tags }
            .map { it.value }
            .toSet()

        assertThat(tagValues).allSatisfy { assertThat(it).isEqualTo(it.lowercase()) }
        assertThat(tagValues).contains("hold_balance", "db_unique", "final_refund", "raced")
    }

    /** 사전 등록 목록에 없는 조합이 와도 도메인 흐름을 죽이지 않고 세기만 한다. */
    @Test
    fun `사전 등록되지 않은 조합도 예외 없이 등록되어 세어진다`() {
        metrics.onDefenseTriggered(DefenseTriggered(DefensePoint.HOLD_BALANCE, DefenseOutcome.STALE))

        assertThat(defenseCount("hold_balance", "stale")).isEqualTo(1.0)
    }
}
