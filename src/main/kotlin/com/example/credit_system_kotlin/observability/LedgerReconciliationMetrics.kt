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

/**
 * 대사 결과를 지표로 내보낸다. 태그는 붙이지 않는다.
 * userId 를 태그로 달면 시계열이 사용자 수만큼 늘어서, 누가 틀렸는지는 대사 작업의 ERROR 로그에서 찾는다.
 */
@Component
class LedgerReconciliationMetrics(
    registry: MeterRegistry,
    private val clock: Clock
) {

    // Gauge는 상태 객체를 약한 참조로만 문다. 지역 변수나 매번 새로 만드는 람다를 넘기면
    // 그 상태가 GC 대상이 되어 게이지가 NaN을 뱉는다. AtomicLong을 필드로 들고 생성자에서
    // 한 번만 등록해, 이 컴포넌트(싱글턴 빈)가 살아있는 한 상태도 함께 살아있게 한다.
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

        // 상태 객체로 `this`(싱글턴 빈)를 넘긴다. Spring 컨텍스트가 이 빈을 강하게 들고
        // 있는 한 GC되지 않으므로, 매 스크레이프마다 lastCompletedAt을 다시 읽어
        // "대사가 멈춘 것" 자체를 드러낼 수 있다.
        Gauge.builder(STALENESS_METRIC, this) { it.stalenessSeconds() }
            .description("마지막 성공 대사로부터 흐른 시간(초). 대사 자체가 멈춘 것을 탐지한다")
            .baseUnit("seconds")
            .register(registry)
    }

    /** 불일치 수와 검사 수는 마지막 주기 값으로 덮어쓰고, 주기 수와 걸린 시간만 쌓는다. */
    @EventListener
    fun onReconciliationCompleted(event: LedgerReconciliationCompleted) {
        mismatchCount.set(event.mismatchCount.toLong())
        checkedCount.set(event.checkedCount.toLong())
        cyclesCounter.increment()
        durationTimer.record(event.duration)
        lastCompletedAt.set(event.completedAt)
    }

    /** 아직 한 번도 대사가 돌지 않았으면 -1을 반환한다. 0은 "방금 성공"과 구분되지 않는다. */
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
