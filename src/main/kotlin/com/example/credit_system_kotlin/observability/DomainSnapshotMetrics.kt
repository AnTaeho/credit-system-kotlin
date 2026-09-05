package com.example.credit_system_kotlin.observability

import com.example.credit_system_kotlin.global.event.DomainSnapshotTaken
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
 * 도메인 상태 스냅샷을 Micrometer 게이지로 승격한다.
 *
 * 여기가 (다른 두 리스너와 함께) Micrometer 를 아는 유일한 곳이다. 게이지의 상태 객체는
 * 전부 이 싱글턴 빈의 필드다 — Micrometer 가 상태를 약한 참조로만 물기 때문에, 지역 변수를
 * 넘기면 GC 이후 NaN 이 된다.
 *
 * 태그는 붙이지 않는다. 특히 organizationId 는 카디널리티가 폭발하므로 절대 금지다.
 */
@Component
class DomainSnapshotMetrics(
    registry: MeterRegistry,
    private val clock: Clock
) {

    private val outstandingHoldCount = AtomicLong(0)
    private val outstandingHoldAmount = AtomicLong(0)
    private val oldestPendingAgeSeconds = AtomicLong(0)
    private val negativeBalanceOrgs = AtomicLong(0)
    private val jobsWithoutHold = AtomicLong(0)
    private val unsettledTerminalJobs = AtomicLong(0)
    private val lastTakenAt = AtomicReference<Instant?>(null)

    private val cyclesCounter: Counter = Counter.builder(CYCLES_METRIC)
        .description("완료한 도메인 스냅샷 주기의 누적 수")
        .register(registry)

    private val durationTimer: Timer = Timer.builder(DURATION_METRIC)
        .description("도메인 스냅샷 한 주기에 걸린 시간")
        .register(registry)

    init {
        Gauge.builder(OUTSTANDING_COUNT_METRIC, outstandingHoldCount) { it.get().toDouble() }
            .description("미결 job 수. COMPLETED/REFUNDED 가 아닌 모든 job 이며 FAILED 도 포함한다")
            .register(registry)

        Gauge.builder(OUTSTANDING_AMOUNT_METRIC, outstandingHoldAmount) { it.get().toDouble() }
            .description("미결 job 에 묶여 있는 크레딧 합계")
            .register(registry)

        // 이 단계의 단일 최중요 지표다. 워커가 죽든, 스케줄러가 죽든, Redis 가 죽든,
        // 스텁 API 가 무한히 지연되든 파이프라인이 멈추면 이 값 하나가 무한히 오른다.
        // 카운터로는 만들 수 없는 지표다 — 아무 코드도 안 불리는 채로 늙어가는 것을 재기 때문이다.
        Gauge.builder(OLDEST_PENDING_AGE_METRIC, oldestPendingAgeSeconds) { it.get().toDouble() }
            .description("가장 오래된 미결 job 의 나이(초). createdAt 기준이라 재시도로 리셋되지 않는다")
            .baseUnit("seconds")
            .register(registry)

        Gauge.builder(NEGATIVE_BALANCE_ORGS_METRIC, negativeBalanceOrgs) { it.get().toDouble() }
            .description("잔액이 음수인 조직 수. 0이 아니면 즉시 사고다")
            .register(registry)

        Gauge.builder(JOBS_WITHOUT_HOLD_METRIC, jobsWithoutHold) { it.get().toDouble() }
            .description("HOLD 원장이 없는 job 수. 0이 아니면 즉시 사고다")
            .register(registry)

        Gauge.builder(UNSETTLED_TERMINAL_METRIC, unsettledTerminalJobs) { it.get().toDouble() }
            .description("종결됐는데 정산 원장이 없는 job 수. 0이 아니면 즉시 사고다")
            .register(registry)

        Gauge.builder(STALENESS_METRIC, this) { it.stalenessSeconds() }
            .description("마지막 성공 스냅샷으로부터 흐른 시간(초). 스냅샷 자체가 멈춘 것을 탐지한다")
            .baseUnit("seconds")
            .register(registry)
    }

    @EventListener
    fun onSnapshotTaken(event: DomainSnapshotTaken) {
        outstandingHoldCount.set(event.outstandingHoldCount)
        outstandingHoldAmount.set(event.outstandingHoldAmount)
        oldestPendingAgeSeconds.set(event.oldestPendingAgeSeconds)
        negativeBalanceOrgs.set(event.negativeBalanceOrgs)
        jobsWithoutHold.set(event.jobsWithoutHold)
        unsettledTerminalJobs.set(event.unsettledTerminalJobs)
        cyclesCounter.increment()
        durationTimer.record(event.duration)
        lastTakenAt.set(event.takenAt)
    }

    /** 아직 한 번도 스냅샷이 찍히지 않았으면 -1을 반환한다. 0은 "방금 성공"과 구분되지 않는다. */
    private fun stalenessSeconds(): Double {
        val last = lastTakenAt.get() ?: return -1.0
        return Duration.between(last, clock.instant()).toMillis() / MILLIS_PER_SECOND
    }

    companion object {
        const val OUTSTANDING_COUNT_METRIC = "credit.hold.outstanding.count"
        const val OUTSTANDING_AMOUNT_METRIC = "credit.hold.outstanding.amount"
        const val OLDEST_PENDING_AGE_METRIC = "credit.job.oldest.pending.age"
        const val NEGATIVE_BALANCE_ORGS_METRIC = "credit.invariant.negative.balance.orgs"
        const val JOBS_WITHOUT_HOLD_METRIC = "credit.invariant.jobs.without.hold"
        const val UNSETTLED_TERMINAL_METRIC = "credit.invariant.unsettled.terminal.jobs"
        const val CYCLES_METRIC = "credit.snapshot.cycles"
        const val DURATION_METRIC = "credit.snapshot.duration"
        const val STALENESS_METRIC = "credit.snapshot.staleness"
        private const val MILLIS_PER_SECOND = 1000.0
    }
}
