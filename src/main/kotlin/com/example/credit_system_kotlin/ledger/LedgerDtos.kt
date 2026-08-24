package com.example.credit_system_kotlin.ledger

import java.time.Instant

data class LedgerResponse(
    val id: Long,
    val type: String,
    val amount: Long,
    val jobId: Long?,
    val createdAt: Instant
) {
    companion object {
        fun from(entry: LedgerEntry): LedgerResponse =
            LedgerResponse(entry.persistedId, entry.type.name, entry.amount, entry.jobId, entry.createdAt)
    }
}

data class LedgerBalanceCheck(
    val organizationId: Long,
    val balance: Long,
    val initialBalance: Long,
    val ledgerSum: Long
)
