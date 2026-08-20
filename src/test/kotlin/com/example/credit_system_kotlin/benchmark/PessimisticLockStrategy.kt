package com.example.credit_system_kotlin.benchmark

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate

class PessimisticLockStrategy(
    private val jdbcTemplate: JdbcTemplate,
    private val transactionTemplate: TransactionTemplate
) : DeductStrategy {

    override val name: String = "pessimistic-lock"

    override fun deduct(accountId: Long, amount: Long): DeductStrategy.DeductOutcome {
        val success = transactionTemplate.execute {
            val balance = jdbcTemplate.queryForObject(
                "SELECT balance FROM bench_account WHERE id = ? FOR UPDATE",
                Long::class.javaObjectType, accountId
            )
            if (balance == null || balance < amount) {
                return@execute false
            }
            val updated = jdbcTemplate.update(
                "UPDATE bench_account SET balance = balance - ? WHERE id = ?",
                amount, accountId
            )
            updated == 1
        }
        return DeductStrategy.DeductOutcome(success == true, 0)
    }
}
