package com.example.credit_system_kotlin.observability

import com.example.credit_system_kotlin.ledger.event.LedgerReconciliationCompleted
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

@Component
class LedgerReconciliationMetrics(
    registry: MeterRegistry,
    private val clock: Clock
) {

    private val mismatchCount = AtomicLong(0)
    private val checkedCount = AtomicLong(0)
    private val lastCompletedAt = AtomicReference<Instant?>(null)

    private val cyclesCounter: Counter = Counter.builder(CYCLES_METRIC)
        .description("완료한 원장 대사 주기의 누적 수")
        .register(registry)

    private val durationTimer: Timer = Timer.builder(DURATION_METRIC)
        .description("원장 대사 한 주기에 걸린 시간")
        .register(registry)

    init {
        Gauge.builder(MISMATCH_METRIC, mismatchCount) { it.get().toDouble() }
            .description("마지막 대사 주기에서 발견된 불일치 사용자 수. 0이 아니면 즉시 사고다")
            .register(registry)

        Gauge.builder(CHECKED_METRIC, checkedCount) { it.get().toDouble() }
            .description("마지막 대사 주기에서 검사한 사용자 수")
            .register(registry)

        Gauge.builder(STALENESS_METRIC, this) { it.stalenessSeconds() }
            .description("마지막 성공 대사로부터 흐른 시간(초). 대사 자체가 멈춘 것을 탐지한다")
            .baseUnit("seconds")
            .register(registry)
    }

    @EventListener
    fun onReconciliationCompleted(event: LedgerReconciliationCompleted) {
        mismatchCount.set(event.mismatchCount.toLong())
        checkedCount.set(event.checkedCount.toLong())
        cyclesCounter.increment()
        durationTimer.record(event.duration)
        lastCompletedAt.set(event.completedAt)
    }

    private fun stalenessSeconds(): Double {
        val last = lastCompletedAt.get() ?: return -1.0
        return Duration.between(last, clock.instant()).toMillis() / MILLIS_PER_SECOND
    }

    companion object {
        private const val MISMATCH_METRIC = "credit.ledger.reconciliation.mismatch"
        private const val CHECKED_METRIC = "credit.ledger.reconciliation.checked"
        private const val CYCLES_METRIC = "credit.ledger.reconciliation.cycles"
        private const val DURATION_METRIC = "credit.ledger.reconciliation.duration"
        private const val STALENESS_METRIC = "credit.ledger.reconciliation.staleness"
        private const val MILLIS_PER_SECOND = 1000.0
    }
}
