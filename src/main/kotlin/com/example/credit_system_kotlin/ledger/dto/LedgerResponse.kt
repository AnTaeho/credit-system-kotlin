package com.example.credit_system_kotlin.ledger.dto

import com.example.credit_system_kotlin.ledger.domain.LedgerEntry
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
