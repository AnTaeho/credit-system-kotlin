package com.example.credit_system_kotlin.observability

import com.example.credit_system_kotlin.job.event.ExternalGenerationCalled
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * INV-04b 계측 — 외부 생성 호출과 그중 중복분을 센다.
 *
 * 요구서 4-3 이 INV-04b 를 지표로 정의했다(2026-09-23). 막는 장치가 아니라 재는 장치다.
 * 판정은 `duplicate / calls` 비율로 하고, 그 상한은 부하 측정(3-C)이 정한다.
 *
 * **태그를 붙이지 않는다.** [WorkerSlotMetrics]·[DomainSnapshotMetrics] 와 같은 이유이고,
 * 덤으로 [MetricsCardinalityConfig] 의 태그 값 상한(point/outcome/detector)을 건드리지 않는다 —
 * 새 태그 값을 하나도 만들지 않으므로 그 주석의 개수도 그대로다.
 *
 * [EventListener] 를 쓰고 `@TransactionalEventListener` 는 쓰지 않는다. [DefenseMetrics] 와
 * 같은 이유다 — "외부 호출이 나갔다"는 사실은 뒤따르는 트랜잭션이 커밋되든 롤백되든 참이고,
 * 오히려 롤백되는 경우가 가장 세고 싶은 사건이다.
 */
@Component
class ExternalCallMetrics(
    registry: MeterRegistry
) {

    // 한 번도 발생하지 않은 시계열이 스크레이프에서 통째로 사라지지 않도록 미리 등록한다.
    private val calls: Counter = Counter.builder(CALLS_METRIC)
        .description("외부 생성 API 로 나간 호출 수. 시도마다 1 이며 성공·실패를 가리지 않는다")
        .register(registry)

    private val duplicateCalls: Counter = Counter.builder(DUPLICATE_CALLS_METRIC)
        .description(
            "같은 job 에 대한 두 번째 이상의 외부 호출 수(attemptNo > 0). INV-04b 는 이 값을 " +
                "막지 않고 센다 — 외부 API 에 멱등키가 없다고 가정하기 때문이다. " +
                "credit.generation.external.calls 로 나눈 비율이 중복 호출 원가의 상한이다"
        )
        .register(registry)

    @EventListener
    fun onExternalGenerationCalled(event: ExternalGenerationCalled) {
        calls.increment()
        if (event.attemptNo > 0) {
            duplicateCalls.increment()
        }
    }

    companion object {
        const val CALLS_METRIC = "credit.generation.external.calls"
        const val DUPLICATE_CALLS_METRIC = "credit.generation.external.duplicate.calls"
    }
}
