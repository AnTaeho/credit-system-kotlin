package com.example.credit_system_kotlin.ledger.scheduling

import com.example.credit_system_kotlin.ledger.dto.LedgerBalanceCheck
import com.example.credit_system_kotlin.ledger.event.LedgerReconciliationCompleted
import com.example.credit_system_kotlin.ledger.repository.LedgerRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

private val log = LoggerFactory.getLogger(LedgerReconciliationTask::class.java)

/** 사용자마다 `initialBalance + 원장 합계` 가 `balance` 와 같은지 주기적으로 맞춰 본다. 어긋나도 고치지 않고 알리기만 한다. */
@Component
@ConditionalOnProperty(prefix = "app.scheduling", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class LedgerReconciliationTask(
    private val ledgerRepository: LedgerRepository,
    private val eventPublisher: ApplicationEventPublisher
) {

    /** 사용자를 id 순으로 [RECONCILE_BATCH_SIZE]명씩 끝까지 훑는다. 한 명에서 예외가 나면 그 사람만 건너뛰고, 다 돌면 결과를 이벤트로 낸다. */
    @Scheduled(fixedDelayString = $$"${app.scheduling.reconciliation-interval-millis:60000}")
    fun reconcile() {
        val startedAt = Instant.now()
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
                    log.warn("원장 대사 항목 처리 실패: userId={}", balanceCheck.userId, e)
                }
                lastId = balanceCheck.userId
            }
        } while (checks.size == RECONCILE_BATCH_SIZE)
        log.info("원장 대사 주기 완료: checkedCount={}, mismatchCount={}", checkedCount, mismatchCount)
        val completedAt = Instant.now()
        eventPublisher.publishEvent(
            LedgerReconciliationCompleted(
                checkedCount = checkedCount,
                mismatchCount = mismatchCount,
                duration = Duration.between(startedAt, completedAt),
                completedAt = completedAt
            )
        )
    }

    /** 어긋나면 사용자와 차액을 ERROR 로 남긴다. 지표에는 건수만 나가서 누가 틀렸는지는 이 로그에만 있다. */
    private fun isBalanceConsistent(balanceCheck: LedgerBalanceCheck): Boolean {
        val expected = balanceCheck.initialBalance + balanceCheck.ledgerSum
        if (expected == balanceCheck.balance) {
            return true
        }
        log.error(
            "원장 대사 불일치 발견: userId={}, balance={}, expected={}, initialBalance={}, ledgerSum={}, diff={}",
            balanceCheck.userId, balanceCheck.balance, expected,
            balanceCheck.initialBalance, balanceCheck.ledgerSum, balanceCheck.balance - expected
        )
        return false
    }

    companion object {
        private const val RECONCILE_BATCH_SIZE = 100
    }
}
