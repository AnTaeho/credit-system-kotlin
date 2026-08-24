package com.example.credit_system_kotlin.organization

data class BalanceResponse(val balance: Long)

data class ChargeRequest(val idemKey: String, val amount: Long)

data class ChargeResponse(val balance: Long, val duplicate: Boolean)
