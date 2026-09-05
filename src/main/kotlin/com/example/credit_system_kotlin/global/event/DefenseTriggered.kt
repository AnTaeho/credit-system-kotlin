package com.example.credit_system_kotlin.global.event

/**
 * 방어 장치가 놓인 지점. 이 시스템의 방어는 전부 같은 모양이다 —
 * 조건부 UPDATE 를 날리고 영향 행 수를 본다.
 */
enum class DefensePoint {
    /** 조건부 잔액 차감(step2). `balance >= cost` 를 WHERE 에 넣은 UPDATE */
    HOLD_BALANCE,

    /** 멱등키(step3). 1차는 애플리케이션 조회, 2차는 DB 유니크 제약 */
    IDEM_KEY,

    /** 워커 선점(step4). HOLDING → PROCESSING 전이를 attemptNo 로 잠근다 */
    WORKER_CLAIM,

    /** 생성 성공 확정(step4). 낡은 세대의 confirm 을 attemptNo 로 무효화한다 */
    CONFIRM,

    /** 실패 전이(step4). 낡은 세대의 실패 보고를 attemptNo 로 무효화한다 */
    MARK_FAILED,

    /** 재시도 투입(step5). 같은 FAILED job 을 두 스캐너가 동시에 집는 경쟁 */
    RETRY_CLAIM,

    /** 최종 환불(step5). 늦게 살아난 워커가 먼저 확정하면 환불을 취소한다 */
    FINAL_REFUND
}

/**
 * 방어 지점을 통과한 시도의 결과.
 *
 * [APPLIED] 를 반드시 함께 세야 한다. 막힌 건수만 세면 분모가 없어 비율을 만들 수 없다.
 * `sum by (point)` 가 그 지점을 통과한 전체 시도 수가 되도록 설계했다.
 */
enum class DefenseOutcome {
    /** 조건부 UPDATE 가 1행을 바꿨다. 이 시도가 이겼다 */
    APPLIED,

    /** 잔액이 모자라 0행. 처음부터 부족했는지 경쟁에서 밀렸는지는 구분할 수 없다 */
    REJECTED,

    /** 멱등키 1차 방어. 애플리케이션이 기존 키를 조회로 먼저 찾았다 */
    APP_HIT,

    /** 멱등키 2차 방어. 조회를 통과한 뒤 DB 유니크 제약이 막았다 */
    DB_UNIQUE,

    /** 선점/투입 경쟁에서 밀려 0행 */
    LOST,

    /** attemptNo 가 어긋난 낡은 세대의 쓰기라 0행 */
    STALE,

    /** 늦은 워커가 먼저 상태를 바꿔 버려 0행 */
    RACED
}

/**
 * 방어 장치가 한 번 가동했음을 알리는 순수 도메인 이벤트다.
 *
 * 새 개념을 만들지 않았다. 이미 코드 곳곳에 있던 `updated == 0` 분기를 이벤트 발행
 * 지점으로 승격했을 뿐이다. 세는 책임은 `observability` 패키지의 리스너가 지고,
 * 이 파일과 발행 지점은 Micrometer 를 모른다.
 *
 * 두 필드 모두 enum 이다. 태그로 나갈 값의 집합이 컴파일 타임에 닫혀 있어야
 * 카디널리티가 자유 문자열로 새지 않는다.
 */
data class DefenseTriggered(
    val point: DefensePoint,
    val outcome: DefenseOutcome
)
