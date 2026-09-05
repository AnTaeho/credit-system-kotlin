package com.example.credit_system_kotlin.observability

import com.example.credit_system_kotlin.ledger.event.LedgerReconciliationCompleted
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class LedgerReconciliationMetricsTest {

    private val registry = SimpleMeterRegistry()
    private val fixedInstant = Instant.parse("2026-09-05T00:00:00Z")
    private val clock = FixedMutableClock(fixedInstant)

    private lateinit var metrics: LedgerReconciliationMetrics

    @BeforeEach
    fun setUp() {
        metrics = LedgerReconciliationMetrics(registry, clock)
    }

    @Test
    fun `이벤트를 받으면 mismatch와 checked 게이지가 그 값이 된다`() {
        metrics.onReconciliationCompleted(
            LedgerReconciliationCompleted(
                checkedCount = 10,
                mismatchCount = 2,
                duration = Duration.ofMillis(150),
                completedAt = fixedInstant
            )
        )

        assertThat(registry.get("credit.ledger.reconciliation.mismatch").gauge().value()).isEqualTo(2.0)
        assertThat(registry.get("credit.ledger.reconciliation.checked").gauge().value()).isEqualTo(10.0)
    }

    @Test
    fun `이벤트를 두 번 받으면 게이지는 마지막 값으로 덮이고 cycles는 2가 된다`() {
        metrics.onReconciliationCompleted(
            LedgerReconciliationCompleted(
                checkedCount = 10,
                mismatchCount = 2,
                duration = Duration.ofMillis(100),
                completedAt = fixedInstant
            )
        )
        metrics.onReconciliationCompleted(
            LedgerReconciliationCompleted(
                checkedCount = 5,
                mismatchCount = 0,
                duration = Duration.ofMillis(200),
                completedAt = fixedInstant.plusSeconds(60)
            )
        )

        assertThat(registry.get("credit.ledger.reconciliation.mismatch").gauge().value()).isEqualTo(0.0)
        assertThat(registry.get("credit.ledger.reconciliation.checked").gauge().value()).isEqualTo(5.0)
        assertThat(registry.get("credit.ledger.reconciliation.cycles").counter().count()).isEqualTo(2.0)
    }

    @Test
    fun `이벤트 전 staleness는 -1이고 이벤트 후 시계를 앞으로 당기면 흐른 초가 나온다`() {
        assertThat(registry.get("credit.ledger.reconciliation.staleness").gauge().value()).isEqualTo(-1.0)

        metrics.onReconciliationCompleted(
            LedgerReconciliationCompleted(
                checkedCount = 1,
                mismatchCount = 0,
                duration = Duration.ofMillis(10),
                completedAt = fixedInstant
            )
        )
        clock.advance(Duration.ofSeconds(30))

        assertThat(registry.get("credit.ledger.reconciliation.staleness").gauge().value()).isEqualTo(30.0)
    }

    @Test
    fun `duration 타이머가 이벤트의 duration을 기록한다`() {
        metrics.onReconciliationCompleted(
            LedgerReconciliationCompleted(
                checkedCount = 1,
                mismatchCount = 0,
                duration = Duration.ofMillis(250),
                completedAt = fixedInstant
            )
        )

        val timer = registry.get("credit.ledger.reconciliation.duration").timer()
        assertThat(timer.count()).isEqualTo(1)
        assertThat(timer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS)).isEqualTo(250.0)
    }

    /** [Clock.fixed] 는 인스턴트를 바꿀 수 없어, 흐르는 시간을 흉내 내려고 직접 변경 가능한 시계를 둔다. */
    private class FixedMutableClock(private var instant: Instant) : Clock() {
        fun advance(duration: Duration) {
            instant = instant.plus(duration)
        }

        override fun getZone(): java.time.ZoneId = ZoneOffset.UTC

        override fun withZone(zone: java.time.ZoneId?): Clock = this

        override fun instant(): Instant = instant
    }
}
