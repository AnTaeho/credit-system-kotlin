package com.example.credit_system_kotlin.job.service

import com.example.credit_system_kotlin.job.domain.Job
import com.example.credit_system_kotlin.job.domain.JobRepository
import com.example.credit_system_kotlin.job.domain.JobStatus
import com.example.credit_system_kotlin.ledger.LedgerEntry
import com.example.credit_system_kotlin.ledger.LedgerRepository
import com.example.credit_system_kotlin.ledger.LedgerType
import com.example.credit_system_kotlin.organization.OrganizationRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

private val log = LoggerFactory.getLogger(JobLifecycleService::class.java)

@Service
class JobLifecycleService(
    private val jobRepository: JobRepository,
    private val organizationRepository: OrganizationRepository,
    private val ledgerRepository: LedgerRepository
) {

    @Transactional
    fun confirm(job: Job, resultUrl: String) {
        val jobId = job.persistedId
        val updated = jobRepository.completeIfAttemptMatches(jobId, resultUrl, job.attemptNo, Instant.now())
        if (updated == 0) {
            log.info("이미 무효화된 시도, confirm 무시: jobId={}, attemptNo={}", jobId, job.attemptNo)
            return
        }
        ledgerRepository.save(LedgerEntry.of(job.organizationId, jobId, LedgerType.CONFIRM, 0))
        log.info("confirm 완료: jobId={}, attemptNo={}", jobId, job.attemptNo)
    }

    @Transactional
    fun markFailed(jobId: Long, attemptNo: Int) {
        val updated = jobRepository.transitionIfStatusAndAttemptMatch(
            jobId, JobStatus.FAILED, JobStatus.PROCESSING, attemptNo, Instant.now()
        )
        if (updated == 0) {
            log.info("이미 무효화된 시도, 실패 처리 무시: jobId={}, attemptNo={}", jobId, attemptNo)
            return
        }
        log.info("실패 처리: jobId={}, attemptNo={}", jobId, attemptNo)
    }

    @Transactional
    fun retry(job: Job) {
        val jobId = job.persistedId
        val updated = jobRepository.incrementAttemptForRetry(jobId, job.attemptNo, Instant.now())
        if (updated == 0) {
            log.info("재시도 투입 경쟁에서 밀림 또는 이미 처리됨: jobId={}, attemptNo={}", jobId, job.attemptNo)
            return
        }
        log.info("재시도 투입: jobId={}, newAttemptNo={}", jobId, job.attemptNo + 1)
    }

    @Transactional
    fun finalRefund(job: Job) {
        val jobId = job.persistedId
        val updated = jobRepository.transitionIfStatusAndAttemptMatch(
            jobId, JobStatus.REFUNDED, JobStatus.FAILED, job.attemptNo, Instant.now()
        )
        if (updated == 0) {
            log.info("이미 늦은 워커가 처리함, 환불 취소: jobId={}, attemptNo={}", jobId, job.attemptNo)
            return
        }

        val orgUpdated = organizationRepository.addBalance(job.organizationId, job.holdAmount, Instant.now())
        check(orgUpdated == 1) {
            "환불 잔액 반영 실패: organization이 존재하지 않음, jobId=$jobId, organizationId=${job.organizationId}"
        }

        ledgerRepository.save(LedgerEntry.of(job.organizationId, jobId, LedgerType.REFUND, job.holdAmount))
        log.info(
            "최종 환불 완료: jobId={}, organizationId={}, amount={}",
            jobId, job.organizationId, job.holdAmount
        )
    }
}
