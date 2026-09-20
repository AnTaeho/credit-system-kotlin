package com.example.credit_system_kotlin.observability

import com.example.credit_system_kotlin.job.worker.WorkerSlots
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

/**
 * 워커 풀의 빈 자리를 게이지로 노출한다.
 *
 * 절대 상한(`app.processing.absolute-timeout-seconds`)은 **돈만 푼다.** 멈춘 워커의 스레드는
 * 돌아오지 않는다 — 깨울 수단이 없기 때문이다. job 은 FAILED → 재시도 → 환불까지 흘러가고
 * 잔액도 맞지만, 슬롯은 하나씩 영구히 사라진다. 그 손실은 job 상태 어디에도 안 적힌다.
 *
 * 이 게이지가 그 자리를 본다. **0 에 붙어 있으면 슬롯이 샜다는 신호다** — 특히
 * `credit.job.recovery{detector="hard_cap"}` 이 오른 뒤에도 값이 돌아오지 않으면 확정이다.
 * 반대로 처리량이 많아 잠깐 0 이 되는 것은 정상이므로, 판정은 "얼마나 오래 0 인가"가 한다.
 * 알람 규칙은 이 조각의 몫이 아니다.
 *
 * 태그는 붙이지 않는다. [DomainSnapshotMetrics] 와 같은 이유다.
 * 상태 객체([workerSlots])는 이 싱글턴 빈의 필드로 잡아 둔다 — Micrometer 가 게이지 상태를
 * 약한 참조로만 물기 때문에, 지역 변수를 넘기면 GC 이후 NaN 이 된다.
 */
@Component
class WorkerSlotMetrics(
    registry: MeterRegistry,
    private val workerSlots: WorkerSlots
) {

    init {
        Gauge.builder(FREE_SLOTS_METRIC, workerSlots) { it.free().toDouble() }
            .description(
                "워커 풀의 빈 실행 슬롯 수. 오래 0 에 붙어 있으면 스레드가 묶여 돌아오지 않는다는 뜻이다. " +
                    "절대 상한 회수는 돈만 풀 뿐 슬롯은 돌려주지 못한다"
            )
            .register(registry)
    }

    companion object {
        const val FREE_SLOTS_METRIC = "credit.worker.slots.free"
    }
}
