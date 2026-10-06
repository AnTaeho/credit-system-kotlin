package com.example.credit_system_kotlin.job.event

/** job 을 회수한 감지 경로. heartbeat 가 없다고 확인한 회수와 못 본 채 내린 회수를 따로 센다. */
enum class RecoveryDetector {
    /** Redis heartbeat 의 TTL 만료로 잡았다. 정상 경로이자 가장 빠른 감지다 */
    HEARTBEAT,

    /**
     * `updatedAt` 정체 스캔으로 잡았고, heartbeat 조회에는 성공했는데 없었다.
     * heartbeat 가 있어야 했는데 없었다는 뜻이므로 **heartbeat 누수 신호**다.
     */
    BACKSTOP,

    /**
     * heartbeat 저장소가 안 보여 `updatedAt` 만 믿고 회수했다. Redis 장애 신호다.
     * 살아 있는 job 을 내렸을 수 있고, 그러면 원래 워커의 confirm 이 0행으로 막힌다.
     */
    BACKSTOP_BLIND
}

/** job 을 FAILED 로 회수했을 때 낸다. [jobId]·[attemptNo] 는 로그용이고, 지표 태그로 쓰면 시계열이 job 수만큼 는다. */
data class JobRecovered(
    val jobId: Long,
    val attemptNo: Int,
    val detector: RecoveryDetector
)
