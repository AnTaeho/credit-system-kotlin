package com.example.credit_system_kotlin.observability

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * 접수 벌크헤드가 지금 무엇을 하고 있는지 보이게 한다.
 *
 * 상한을 건 뒤 배경 작업이 살아났는지는 다른 지표가 답하지만, **접수가 실제로 상한에 닿았는지**
 * 는 여기서만 보인다. 이 두 값이 없으면 "상한이 커서 아무 일도 안 일어난" 경우와 "상한이 걸려
 * 접수가 기다린" 경우를 구분할 수 없다.
 *
 * - [PERMITS_USED_METRIC]: 지금 쓰이고 있는 허가 수. 상한에 붙어 있으면 접수가 묶여 있다는 뜻이다
 * - [WAIT_METRIC]: 허가를 기다린 시간. 획득할 때마다 기록하므로 count 는 접수 건수와 같고,
 *   sum 이 0 에 가까우면 상한에 닿지 않았다는 뜻이다
 *
 * 태그는 붙이지 않는다. [DomainSnapshotMetrics] 와 같은 이유이고, 카디널리티 상한
 * ([MetricsCardinalityConfig])을 건드리지 않는다. 게이지가 무는 상태 객체([permits])는
 * 필드로 잡아 둔다 — Micrometer 는 약한 참조로만 물기 때문이다.
 */
class IntakeBulkheadMetrics(
    registry: MeterRegistry,
    private val permits: Semaphore,
    private val totalPermits: Int
) {

    private val waitTimer: Timer = Timer.builder(WAIT_METRIC)
        .description("접수 요청이 벌크헤드 허가를 기다린 시간. 획득할 때마다 기록한다")
        .register(registry)

    init {
        Gauge.builder(PERMITS_USED_METRIC, permits) { (totalPermits - it.availablePermits()).toDouble() }
            .description("접수 경로가 지금 쓰고 있는 벌크헤드 허가 수. 상한에 붙어 있으면 접수가 상한에 닿았다는 뜻이다")
            .register(registry)
    }

    fun recordWait(nanos: Long) {
        waitTimer.record(nanos, TimeUnit.NANOSECONDS)
    }

    companion object {
        const val PERMITS_USED_METRIC = "credit.intake.bulkhead.permits.used"
        const val WAIT_METRIC = "credit.intake.bulkhead.wait"
    }
}
