package com.example.credit_system_kotlin.global.event

import java.time.Duration
import java.time.Instant

data class DomainSnapshotTaken(
    // 미결 job 수
    val outstandingHoldCount: Long,
    // 미결 job 의 holdAmount 합. 지금 묶여 있는 돈의 총액
    val outstandingHoldAmount: Long,
    // 가장 오래된 미결 job 의 나이(초). 재시도로 리셋되지 않게 createdAt 부터 잰다
    val oldestPendingAgeSeconds: Long,
    // balance < 0 인 사용자 수. 0 이 아니면 즉시 사고다
    val negativeBalanceUsers: Long,
    // HOLD 원장이 없는 job 수. 0 이어야 한다
    val jobsWithoutHold: Long,
    // 종결됐는데 정산 원장이 없는 job 수. 0 이어야 한다
    val unsettledTerminalJobs: Long,
    val duration: Duration,
    val takenAt: Instant
)
