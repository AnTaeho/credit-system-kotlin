package com.example.credit_system_kotlin.ledger.event

import java.time.Duration
import java.time.Instant

data class LedgerReconciliationCompleted(
    val checkedCount: Int,
    val mismatchCount: Int,
    val duration: Duration,
    val completedAt: Instant
)
