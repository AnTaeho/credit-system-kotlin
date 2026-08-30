package com.example.credit_system_kotlin.job.service

import com.example.credit_system_kotlin.job.domain.Job
import com.example.credit_system_kotlin.job.repository.JobRepository
import com.example.credit_system_kotlin.ledger.domain.LedgerEntry
import com.example.credit_system_kotlin.ledger.repository.LedgerRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

private val log = LoggerFactory.getLogger(JobLifecycleService::class.java)

@Service
class JobLifecycleService(
    private val jobRepository: JobRepository,
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
        ledgerRepository.save(LedgerEntry.confirm(job.organizationId, jobId))
        log.info("confirm 완료: jobId={}, attemptNo={}", jobId, job.attemptNo)
    }

    @Transactional
    fun markFailed(jobId: Long, attemptNo: Int) {
        val updated = jobRepository.failIfProcessing(jobId, attemptNo, Instant.now())
        if (updated == 0) {
            log.info("이미 무효화된 시도, 실패 처리 무시: jobId={}, attemptNo={}", jobId, attemptNo)
            return
        }
        log.info("실패 처리: jobId={}, attemptNo={}", jobId, attemptNo)
    }
}
