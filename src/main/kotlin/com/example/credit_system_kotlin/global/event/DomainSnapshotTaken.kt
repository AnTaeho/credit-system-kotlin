package com.example.credit_system_kotlin.global.event

import java.time.Duration
import java.time.Instant

/**
 * 한 시점의 DB 상태를 센 값. 미결은 COMPLETED·REFUNDED 가 아닌 job 전부다.
 * FAILED 도 재시도나 환불을 기다리며 돈이 묶여 있어 미결로 친다.
 */
data class DomainSnapshotTaken(
    /** 미결 job 수. */
    val outstandingHoldCount: Long,
    /** 미결 job 의 holdAmount 합. 지금 묶여 있는 돈의 총액이다. */
    val outstandingHoldAmount: Long,
    /**
     * 가장 오래된 미결 job 의 나이(초). 미결이 없으면 0 이다.
     * `createdAt` 부터 잰다. `updatedAt` 으로 재면 재시도 때마다 0 으로 돌아가 재시도만 도는 job 을 놓친다.
     */
    val oldestPendingAgeSeconds: Long,
    /** balance < 0 인 사용자 수. 불변식이라 0 이 아니면 즉시 사고다. */
    val negativeBalanceUsers: Long,
    /** HOLD 원장이 없는 job 수. 돈을 묶었다는 기록 없이 job 이 생겼다는 뜻이라 0 이어야 한다. */
    val jobsWithoutHold: Long,
    /** 종결(COMPLETED/REFUNDED)됐는데 정산 원장(CONFIRM/REFUND)이 없는 job 수. 0 이어야 한다. */
    val unsettledTerminalJobs: Long,
    val duration: Duration,
    val takenAt: Instant
)
