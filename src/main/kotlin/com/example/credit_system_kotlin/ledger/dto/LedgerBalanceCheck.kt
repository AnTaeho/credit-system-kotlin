package com.example.credit_system_kotlin.ledger.dto

data class LedgerBalanceCheck(
    val organizationId: Long,
    val balance: Long,
    val initialBalance: Long,
    val ledgerSum: Long
)
