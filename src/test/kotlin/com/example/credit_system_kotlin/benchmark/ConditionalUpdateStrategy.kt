package com.example.credit_system_kotlin.benchmark

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate

class ConditionalUpdateStrategy(
    private val jdbcTemplate: JdbcTemplate,
    private val transactionTemplate: TransactionTemplate
) : DeductStrategy {

    override val name: String = "conditional-update"

    override fun deduct(accountId: Long, amount: Long): DeductStrategy.DeductOutcome {
        val success = transactionTemplate.execute {
            val updated = jdbcTemplate.update(
                "UPDATE bench_account SET balance = balance - ? WHERE id = ? AND balance >= ?",
                amount, accountId, amount
            )
            updated == 1
        }
        return DeductStrategy.DeductOutcome(success == true, 0)
    }
}
