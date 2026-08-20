package com.example.credit_system_kotlin.benchmark

interface DeductStrategy {

    val name: String

    fun deduct(accountId: Long, amount: Long): DeductOutcome

    data class DeductOutcome(val success: Boolean, val retries: Int)
}
