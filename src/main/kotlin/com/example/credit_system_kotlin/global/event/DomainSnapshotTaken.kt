package com.example.credit_system_kotlin.global.event

import java.time.Duration
import java.time.Instant

/**
 * 도메인 상태를 한 시점에 통째로 찍은 스냅샷이다.
 *
 * 카운터(step7-2)는 "코드가 불렸다"만 센다. 코드가 안 불린 사고 — 워커가 통째로 죽어
 * confirm 이 영영 호출되지 않는 경우 — 는 어떤 카운터로도 잡히지 않는다. 없는 것은 셀 수
 * 없기 때문이다. 그래서 실행을 세는 지표와 별개로, 코드 경로와 무관하게 DB 에 "지금 이
 * 상태인 row 가 몇 개냐"를 직접 묻는 지표가 필요하다. 이 이벤트가 그 질문의 답이다.
 *
 * `LedgerReconciliationCompleted` 와 같은 원칙으로 Micrometer 를 모른다. 발행은
 * `DomainSnapshotTask`, 집계는 `observability` 패키지의 리스너가 맡는다.
 *
 * "미결(pending)"은 `status NOT IN (COMPLETED, REFUNDED)` 로 정의한다. HOLDING,
 * PROCESSING 은 물론 FAILED 도 미결이다 — 재시도나 환불을 기다리는 중이고, 그 job 의
 * 돈은 아직 묶여 있다.
 */
data class DomainSnapshotTaken(
    /** 미결 job 수. */
    val outstandingHoldCount: Long,
    /** 미결 job 의 holdAmount 합. 지금 묶여 있는 돈의 총액이다. */
    val outstandingHoldAmount: Long,
    /**
     * 가장 오래된 미결 job 의 나이(초). 미결이 없으면 0 이다.
     *
     * `updatedAt` 이 아니라 `createdAt` 기준이다. 이 값은 "돈이 얼마나 오래 묶여 있나"를
     * 재는 것이지 "현재 상태에 얼마나 머물렀나"를 재는 것이 아니다. `updatedAt` 기준이면
     * 재시도가 나이를 0 으로 리셋해 버려서, 영원히 재시도만 도는 job 을 놓친다.
     */
    val oldestPendingAgeSeconds: Long,
    /** balance < 0 인 조직 수. 불변식이라 0 이 아니면 즉시 사고다. */
    val negativeBalanceOrgs: Long,
    /** HOLD 원장이 없는 job 수. 돈을 묶었다는 기록 없이 job 이 생겼다는 뜻이라 0 이어야 한다. */
    val jobsWithoutHold: Long,
    /** 종결(COMPLETED/REFUNDED)됐는데 정산 원장(CONFIRM/REFUND)이 없는 job 수. 0 이어야 한다. */
    val unsettledTerminalJobs: Long,
    val duration: Duration,
    val takenAt: Instant
)
