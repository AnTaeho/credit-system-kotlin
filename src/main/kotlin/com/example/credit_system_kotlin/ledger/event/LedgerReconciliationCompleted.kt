package com.example.credit_system_kotlin.ledger.event

import java.time.Duration
import java.time.Instant

/**
 * 원장 대사 한 주기가 끝났음을 알리는 순수 도메인 이벤트다.
 *
 * `LedgerReconciliationTask` 는 이 이벤트를 발행하기만 하고, 집계(게이지·카운터 갱신)는
 * `observability` 패키지의 리스너가 전담한다. 이 파일은 Micrometer 를 몰라야 한다 —
 * 관측 방식이 바뀌어도(Prometheus → 다른 것) 이 이벤트와 발행 지점은 흔들리지 않는다.
 */
data class LedgerReconciliationCompleted(
    val checkedCount: Int,
    val mismatchCount: Int,
    val duration: Duration,
    val completedAt: Instant
)
