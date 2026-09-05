package com.example.credit_system_kotlin.job.event

/**
 * 죽은 job 을 되살린 감지 장치. 두 겹으로 두고, 어느 쪽이 잡았는지를 구분해서 센다.
 */
enum class RecoveryDetector {
    /** Redis heartbeat 의 TTL 만료로 잡았다. 정상 경로다 */
    HEARTBEAT,

    /** `updatedAt` 정체 스캔으로 잡았다. heartbeat 가 놓친 것을 뒤에서 받아냈다는 뜻이다 */
    BACKSTOP
}

/**
 * 죽은 것으로 판정된 job 을 FAILED 로 회수했음을 알리는 이벤트다.
 *
 * [jobId] 와 [attemptNo] 는 로그로 개별 건을 추적하기 위한 것이지 지표 태그가 아니다.
 * 태그로 쓰면 job 수만큼 시계열이 늘어나 카디널리티가 폭발한다 — 집계는 지표가,
 * 개별 식별은 로그가 맡는다.
 */
data class JobRecovered(
    val jobId: Long,
    val attemptNo: Int,
    val detector: RecoveryDetector
)
