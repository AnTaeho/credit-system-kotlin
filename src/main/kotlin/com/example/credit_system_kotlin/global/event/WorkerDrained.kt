package com.example.credit_system_kotlin.global.event

import java.time.Duration

/**
 * 배포 드레인이 진행 중 job 에게 내린 결말.
 *
 * enum 이라 태그로 나갈 값의 집합이 컴파일 타임에 닫혀 있다. [DefenseOutcome] 과 같은 이유다.
 */
enum class DrainOutcome {
    /** 드레인 상한 안에 스스로 끝났다. 회수가 아니라 완료로 종결된 job */
    DRAINED,

    /** 상한을 넘겨 기다리기를 포기했다. 이 job 은 PROCESSING 으로 남아 회수 대상이 된다 */
    ABANDONED
}

/**
 * 워커 드레인이 시작됐다. 이 시점 이후로 디스패처는 새 job 을 선점하지 않는다.
 *
 * 완료 이벤트와 따로 두는 이유는 "드레인이 시작된 순간"에만 관찰할 수 있는 것이 있기 때문이다 —
 * 이때는 진행 중 job 이 아직 살아 있고 heartbeat 도 갱신되는 중이다. 완료 이벤트가 나올 때는
 * 둘 다 이미 끝나 있어서 같은 질문을 던질 수 없다.
 */
data class WorkerDrainStarted(
    /** 드레인 시작 시점에 실행 중이던 job 수 */
    val inFlight: Int
)

/**
 * 워커 드레인이 끝났다.
 *
 * [DefenseTriggered] 와 같은 원칙으로 Micrometer 를 모른다. 발행은 `GenerationWorkerLifecycle`,
 * 집계는 `observability` 패키지의 리스너가 맡는다.
 */
data class WorkerDrainCompleted(
    /** 드레인 시작 시점에 실행 중이던 job 수. [abandoned] 를 뺀 나머지가 완료로 끝난 수다 */
    val inFlightAtStart: Int,
    /** 상한을 넘겨 기다리기를 포기한 job 수. 0 이 아니면 배포가 job 을 회수에 떠넘겼다는 뜻이다 */
    val abandoned: Int,
    /** 드레인에 실제로 걸린 시간. 상한에 얼마나 가까웠는지가 다음 배포의 여유를 말해 준다 */
    val duration: Duration
) {
    /** 완료로 끝난 job 수 */
    val drained: Int get() = inFlightAtStart - abandoned
}
