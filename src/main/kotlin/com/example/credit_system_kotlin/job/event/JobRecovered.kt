package com.example.credit_system_kotlin.job.event

/**
 * 죽은 job 을 되살린 감지 장치. 두 겹으로 두고, 어느 쪽이 잡았는지를 구분해서 센다.
 *
 * 백스톱이 둘로 갈리는 것은 **판정의 확신도가 다르기 때문**이다. heartbeat 를 조회해 보고
 * 없다고 확인한 회수와, 조회 자체를 못 한 채 `updatedAt` 만 믿고 내린 회수는 같은 사실이 아니다.
 * 같은 라벨로 뭉치면 Redis 장애가 heartbeat 누수로 오독된다.
 */
enum class RecoveryDetector {
    /** Redis heartbeat 의 TTL 만료로 잡았다. 정상 경로이자 가장 빠른 감지다 */
    HEARTBEAT,

    /**
     * `updatedAt` 정체 스캔으로 잡았고, heartbeat 조회에는 성공했는데 없었다.
     * heartbeat 가 있어야 했는데 없었다는 뜻이므로 **heartbeat 누수 신호**다.
     */
    BACKSTOP,

    /**
     * heartbeat 저장소 자체가 보이지 않아 `updatedAt` 만 믿고 회수했다.
     * **Redis 장애 신호**이며, 이 회수는 **오탐일 수 있다** — 살아 있는 job 을 죽었다고
     * 판정했을 가능성이 남는다. 오탐이면 원래 워커가 뒤늦게 돌아왔을 때
     * attemptNo CAS 가 0행으로 막아 `confirm/stale` 이 함께 오른다.
     */
    BACKSTOP_BLIND
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
