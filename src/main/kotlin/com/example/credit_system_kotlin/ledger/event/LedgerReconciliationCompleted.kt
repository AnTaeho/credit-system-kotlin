package com.example.credit_system_kotlin.ledger.event

import java.time.Duration
import java.time.Instant

/** 대사 한 주기가 끝날 때마다 발행한다. 검사한 사용자가 0명이어도 나온다. */
data class LedgerReconciliationCompleted(
    val checkedCount: Int,
    val mismatchCount: Int,
    val duration: Duration,
    val completedAt: Instant
)
