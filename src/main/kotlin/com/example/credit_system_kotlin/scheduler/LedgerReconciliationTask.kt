package com.example.credit_system_kotlin.scheduler

import com.example.credit_system_kotlin.ledger.dto.LedgerBalanceCheck
import com.example.credit_system_kotlin.ledger.repository.LedgerRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

private val log = LoggerFactory.getLogger(LedgerReconciliationTask::class.java)

@Component
@ConditionalOnProperty(prefix = "app.scheduling", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class LedgerReconciliationTask(
    private val ledgerRepository: LedgerRepository
) {

    @Scheduled(fixedDelayString = $$"${app.scheduling.reconciliation-interval-millis:60000}")
    fun reconcile() {
        var checkedCount = 0
        var mismatchCount = 0
        var lastId = 0L
        do {
            val checks = ledgerRepository.findBalanceChecksAfter(lastId, PageRequest.of(0, RECONCILE_BATCH_SIZE))
            for (balanceCheck in checks) {
                try {
                    if (!isBalanceConsistent(balanceCheck)) {
                        mismatchCount++
                    }
                    checkedCount++
                } catch (e: RuntimeException) {
                    log.warn("원장 대사 항목 처리 실패: organizationId={}", balanceCheck.organizationId, e)
                }
                lastId = balanceCheck.organizationId
            }
        } while (checks.size == RECONCILE_BATCH_SIZE)
        log.info("원장 대사 주기 완료: checkedCount={}, mismatchCount={}", checkedCount, mismatchCount)
    }

    private fun isBalanceConsistent(balanceCheck: LedgerBalanceCheck): Boolean {
        val expected = balanceCheck.initialBalance + balanceCheck.ledgerSum
        if (expected == balanceCheck.balance) {
            return true
        }
        log.error(
            "원장 대사 불일치 발견: organizationId={}, balance={}, expected={}, initialBalance={}, ledgerSum={}, diff={}",
            balanceCheck.organizationId, balanceCheck.balance, expected,
            balanceCheck.initialBalance, balanceCheck.ledgerSum, balanceCheck.balance - expected
        )
        return false
    }

    companion object {
        private const val RECONCILE_BATCH_SIZE = 100
    }
}
