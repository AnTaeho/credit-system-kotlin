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
 * 원장 대사 결과를 Micrometer 지표로 승격한다.
 *
 * `LedgerReconciliationTask`(도메인/스케줄러)는 [LedgerReconciliationCompleted] 이벤트만
 * 발행하고, 세는 책임은 이 컴포넌트가 진다. 관측 방식이 바뀌어도(Prometheus → 다른 것)
 * 도메인 코드는 흔들리지 않는다.
 *
 * 라벨(tag)은 붙이지 않는다. 특히 organizationId 는 카디널리티가 폭발하므로 절대 금지 —
 * 개별 조직 식별은 로그(`LedgerReconciliationTask` 의 ERROR 로그)의 몫이고, 여기는
 * 전역 집계만 담당한다.
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
            .description("마지막 대사 주기에서 발견된 불일치 조직 수. 0이 아니면 즉시 사고다")
            .register(registry)

        Gauge.builder(CHECKED_METRIC, checkedCount) { it.get().toDouble() }
            .description("마지막 대사 주기에서 검사한 조직 수")
            .register(registry)

        // 상태 객체로 `this`(싱글턴 빈)를 넘긴다. Spring 컨텍스트가 이 빈을 강하게 들고
        // 있는 한 GC되지 않으므로, 매 스크레이프마다 lastCompletedAt을 다시 읽어
        // "대사가 멈춘 것" 자체를 드러낼 수 있다.
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
