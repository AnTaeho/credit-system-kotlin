package com.example.credit_system_kotlin.observability

import com.example.credit_system_kotlin.global.event.DrainOutcome
import com.example.credit_system_kotlin.global.event.WorkerDrainCompleted
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * 배포 드레인의 결말을 Micrometer 카운터로 승격한다.
 *
 * `GenerationWorkerLifecycle` 은 [WorkerDrainCompleted] 만 발행하고, 세는 책임은 여기가 진다.
 * 다른 리스너들과 같은 원칙이다.
 *
 * 카운터는 하나만 둔다. 세는 단위는 "드레인 횟수"가 아니라 **job 수**다 — 배포가 몇 번
 * 있었는지는 배포 도구가 알고, 지표가 답해야 할 질문은 "그 배포가 job 을 몇 개 회수에
 * 떠넘겼나"이기 때문이다. `outcome=abandoned` 가 0 이 아닌 배포는 드레인 상한이 실제 처리
 * 시간보다 짧다는 신호다.
 *
 * 태그는 [DrainOutcome] 하나뿐이고 값이 둘이다. 카디널리티가 늘어날 자리가 없다.
 */
@Component
class WorkerDrainMetrics(
    registry: MeterRegistry
) {

    // 한 번도 배포가 없었던 인스턴스에도 시계열이 있어야 rate()/increase() 가 성립한다.
    // DefenseMetrics 와 같은 이유로 유효 조합 전부를 0 으로 깔아 둔다.
    private val counters: Map<DrainOutcome, Counter> = DrainOutcome.entries.associateWith { outcome ->
        Counter.builder(DRAIN_JOBS_METRIC)
            .description(
                "배포 드레인이 처리한 진행 중 job 수. outcome=drained 는 완료로 끝난 것, " +
                    "abandoned 는 상한을 넘겨 회수에 떠넘긴 것이다"
            )
            .tag("outcome", outcome.name.lowercase())
            .register(registry)
    }

    @EventListener
    fun onDrainCompleted(event: WorkerDrainCompleted) {
        increment(DrainOutcome.DRAINED, event.drained)
        increment(DrainOutcome.ABANDONED, event.abandoned)
    }

    private fun increment(outcome: DrainOutcome, count: Int) {
        if (count > 0) {
            counters.getValue(outcome).increment(count.toDouble())
        }
    }

    companion object {
        const val DRAIN_JOBS_METRIC = "credit.worker.drain.jobs"
    }
}
