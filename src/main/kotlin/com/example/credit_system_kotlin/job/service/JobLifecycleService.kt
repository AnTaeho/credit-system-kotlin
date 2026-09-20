package com.example.credit_system_kotlin.job.service

import com.example.credit_system_kotlin.global.config.AppProperties
import com.example.credit_system_kotlin.global.event.DefenseOutcome
import com.example.credit_system_kotlin.global.event.DefensePoint
import com.example.credit_system_kotlin.global.event.DefenseTriggered
import com.example.credit_system_kotlin.job.domain.Job
import com.example.credit_system_kotlin.job.repository.JobRepository
import com.example.credit_system_kotlin.ledger.domain.LedgerEntry
import com.example.credit_system_kotlin.ledger.repository.LedgerRepository
import com.example.credit_system_kotlin.user.repository.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

private val log = LoggerFactory.getLogger(JobLifecycleService::class.java)

@Service
class JobLifecycleService(
    private val jobRepository: JobRepository,
    private val userRepository: UserRepository,
    private val ledgerRepository: LedgerRepository,
    private val eventPublisher: ApplicationEventPublisher,
    private val appProperties: AppProperties,
    private val clock: Clock
) {

    @Transactional
    fun confirm(job: Job, resultUrl: String) {
        val jobId = job.persistedId
        val updated = jobRepository.completeIfAttemptMatches(jobId, resultUrl, job.attemptNo, clock.instant())
        if (updated == 0) {
            log.info("이미 무효화된 시도, confirm 무시: jobId={}, attemptNo={}", jobId, job.attemptNo)
            eventPublisher.publishEvent(DefenseTriggered(DefensePoint.CONFIRM, DefenseOutcome.STALE))
            return
        }
        eventPublisher.publishEvent(DefenseTriggered(DefensePoint.CONFIRM, DefenseOutcome.APPLIED))
        ledgerRepository.save(LedgerEntry.confirm(job.userId, jobId))
        log.info("confirm 완료: jobId={}, attemptNo={}", jobId, job.attemptNo)
    }

    @Transactional
    fun markFailed(jobId: Long, attemptNo: Int) {
        val updated = jobRepository.failIfProcessing(jobId, attemptNo, clock.instant())
        if (updated == 0) {
            log.info("이미 무효화된 시도, 실패 처리 무시: jobId={}, attemptNo={}", jobId, attemptNo)
            eventPublisher.publishEvent(DefenseTriggered(DefensePoint.MARK_FAILED, DefenseOutcome.STALE))
            return
        }
        eventPublisher.publishEvent(DefenseTriggered(DefensePoint.MARK_FAILED, DefenseOutcome.APPLIED))
        log.info("실패 처리: jobId={}, attemptNo={}", jobId, attemptNo)
    }

    /**
     * 재시도를 투입하되 **바로 잡히지 않게** 한다.
     *
     * 상태만 HOLDING 으로 되돌리면 다음 디스패치 주기(0.5초)에 곧바로 다시 잡혀서, 외부가
     * 죽어 있을 때 시도 상한을 몇 초 만에 태우고 외부 호출 비용만 상한 횟수만큼 나간다.
     * 그래서 `nextAttemptAt` 에 backoff 만큼 미래를 적어 두고, 디스패처가 그 시각을 본다.
     *
     * 넘기는 [job] 은 방금 실패한 시도라 `attemptNo` 가 곧 몇 번째 재시도인지를 말해 준다 —
     * 0 이면 첫 재시도(base), 1 이면 두 번째(base × multiplier).
     */
    @Transactional
    fun retry(job: Job) {
        val jobId = job.persistedId
        val now = clock.instant()
        val retryCount = job.attemptNo + 1
        val delay = appProperties.generation.retryBackoff.delayFor(retryCount)
        val nextAttemptAt = now.plus(delay)
        val updated = jobRepository.incrementAttemptForRetry(jobId, job.attemptNo, nextAttemptAt, now)
        if (updated == 0) {
            log.info("재시도 투입 경쟁에서 밀림 또는 이미 처리됨: jobId={}, attemptNo={}", jobId, job.attemptNo)
            eventPublisher.publishEvent(DefenseTriggered(DefensePoint.RETRY_CLAIM, DefenseOutcome.LOST))
            return
        }
        eventPublisher.publishEvent(DefenseTriggered(DefensePoint.RETRY_CLAIM, DefenseOutcome.APPLIED))
        log.info(
            "재시도 투입: jobId={}, newAttemptNo={}, {}초 뒤부터 가능",
            jobId, retryCount, delay.seconds
        )
    }

    @Transactional
    fun finalRefund(job: Job) {
        val jobId = job.persistedId
        val updated = jobRepository.refundIfFailed(jobId, job.attemptNo, clock.instant())
        if (updated == 0) {
            log.info("이미 늦은 워커가 처리함, 환불 취소: jobId={}, attemptNo={}", jobId, job.attemptNo)
            eventPublisher.publishEvent(DefenseTriggered(DefensePoint.FINAL_REFUND, DefenseOutcome.RACED))
            return
        }
        eventPublisher.publishEvent(DefenseTriggered(DefensePoint.FINAL_REFUND, DefenseOutcome.APPLIED))

        val userUpdated = userRepository.addBalance(job.userId, job.holdAmount, clock.instant())
        check(userUpdated == 1) {
            "환불 잔액 반영 실패: user이 존재하지 않음, jobId=$jobId, userId=${job.userId}"
        }

        ledgerRepository.save(LedgerEntry.refund(job.userId, jobId, job.holdAmount))
        log.info(
            "최종 환불 완료: jobId={}, userId={}, amount={}",
            jobId, job.userId, job.holdAmount
        )
    }
}
