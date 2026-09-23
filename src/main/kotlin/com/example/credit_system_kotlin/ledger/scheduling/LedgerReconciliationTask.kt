package com.example.credit_system_kotlin.ledger.scheduling

import com.example.credit_system_kotlin.ledger.dto.LedgerBalanceCheck
import com.example.credit_system_kotlin.ledger.event.LedgerReconciliationCompleted
import com.example.credit_system_kotlin.ledger.repository.LedgerRepository
import com.example.credit_system_kotlin.user.repository.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

private val log = LoggerFactory.getLogger(LedgerReconciliationTask::class.java)

@Component
@ConditionalOnProperty(prefix = "app.scheduling", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class LedgerReconciliationTask(
    private val ledgerRepository: LedgerRepository,
    private val userRepository: UserRepository,
    private val eventPublisher: ApplicationEventPublisher
) {

    @Scheduled(fixedDelayString = $$"${app.scheduling.reconciliation-interval-millis:60000}")
    fun reconcile() {
        val startedAt = Instant.now()
        var checkedCount = 0
        var mismatchCount = 0
        var lastId = 0L
        // 한 페이지를 두 단계로 읽는다. 검사할 사용자 id 를 먼저 확정하고(PK 범위 스캔), 그 id 들의
        // 원장만 집계한다. 예전처럼 조인·집계 쿼리에 LIMIT 100 을 걸면 LIMIT 이 GROUP BY 뒤에 걸려
        // 100명을 얻으려고 원장 전체를 훑었다 — 실측으로 1억 행에서 끝나지 않았고 같은 DB 의 조회
        // API p99 를 10ms 에서 8,489ms 로 끌어올렸다(`docs/SYSTEM.md` 4절).
        // 그래서 종료 판정도 집계 결과 수가 아니라 "읽은 사용자 수"(id 페이지 크기)로 한다.
        do {
            val userIds = userRepository.findIdsAfter(lastId, PageRequest.of(0, RECONCILE_BATCH_SIZE))
            if (userIds.isEmpty()) {
                break
            }
            val checks = ledgerRepository.findBalanceChecksFor(userIds)
            for (balanceCheck in checks) {
                try {
                    if (!isBalanceConsistent(balanceCheck)) {
                        mismatchCount++
                    }
                    checkedCount++
                } catch (e: RuntimeException) {
                    log.warn("원장 대사 항목 처리 실패: userId={}", balanceCheck.userId, e)
                }
            }
            lastId = userIds.last()
        } while (userIds.size == RECONCILE_BATCH_SIZE)
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
