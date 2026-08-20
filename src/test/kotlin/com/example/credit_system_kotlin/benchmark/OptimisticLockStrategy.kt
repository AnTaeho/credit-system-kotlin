package com.example.credit_system_kotlin.benchmark

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.transaction.support.TransactionTemplate

class OptimisticLockStrategy(
    private val jdbcTemplate: JdbcTemplate,
    private val requiresNewTransactionTemplate: TransactionTemplate
) : DeductStrategy {

    override val name: String = "optimistic-lock"

    override fun deduct(accountId: Long, amount: Long): DeductStrategy.DeductOutcome {
        for (attempt in 0 until MAX_ATTEMPTS) {
            when (attemptOnce(accountId, amount)) {
                AttemptResult.INSUFFICIENT -> return DeductStrategy.DeductOutcome(false, attempt)
                AttemptResult.SUCCESS -> return DeductStrategy.DeductOutcome(true, attempt)
                AttemptResult.CONFLICT -> Unit
            }
        }
        return DeductStrategy.DeductOutcome(false, MAX_ATTEMPTS - 1)
    }

    /**
     * Java 원본은 이 세 갈래를 `Boolean` 의 true / false / null 로 구분했다.
     * Kotlin에서 `Boolean?` 을 트랜잭션 콜백 밖으로 흘리면 읽기 어려워서 enum으로 바꿨다.
     * 분기 조건과 재시도 횟수 계산은 원본과 같다.
     */
    private enum class AttemptResult { SUCCESS, CONFLICT, INSUFFICIENT }

    private fun attemptOnce(accountId: Long, amount: Long): AttemptResult =
        requiresNewTransactionTemplate.execute {
            val snapshot = jdbcTemplate.queryForObject(
                "SELECT balance, version FROM bench_account WHERE id = ?",
                ACCOUNT_SNAPSHOT_ROW_MAPPER, accountId
            )
            if (snapshot == null || snapshot.balance < amount) {
                return@execute AttemptResult.INSUFFICIENT
            }
            val updated = jdbcTemplate.update(
                "UPDATE bench_account SET balance = balance - ?, version = version + 1 WHERE id = ? AND version = ?",
                amount, accountId, snapshot.version
            )
            if (updated == 1) AttemptResult.SUCCESS else AttemptResult.CONFLICT
        } ?: AttemptResult.CONFLICT

    private data class AccountSnapshot(val balance: Long, val version: Long)

    companion object {
        private const val MAX_ATTEMPTS = 50

        private val ACCOUNT_SNAPSHOT_ROW_MAPPER = RowMapper { rs, _ ->
            AccountSnapshot(rs.getLong("balance"), rs.getLong("version"))
        }
    }
}
