package com.example.credit_system_kotlin.job.scheduling

import com.example.credit_system_kotlin.global.config.AppProperties
import com.example.credit_system_kotlin.heartbeat.HeartbeatRegistry
import com.example.credit_system_kotlin.job.domain.Job
import com.example.credit_system_kotlin.job.domain.JobStatus
import com.example.credit_system_kotlin.job.repository.JobRepository
import com.example.credit_system_kotlin.job.service.JobLifecycleService
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant

private val log = LoggerFactory.getLogger(DeadJobRecoveryTask::class.java)

@Component
@ConditionalOnProperty(prefix = "app.scheduling", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class DeadJobRecoveryTask(
    private val heartbeatRegistry: HeartbeatRegistry,
    private val jobRepository: JobRepository,
    private val jobLifecycleService: JobLifecycleService,
    private val appProperties: AppProperties
) {

    @Scheduled(fixedDelayString = $$"${app.scheduling.dead-job-scan-interval-millis:5000}")
    fun scan() {
        markExpiredJobsAsFailed()
        markStalledJobsAsFailed()
        retryOrRefundFailedJobs()
    }

    private fun markExpiredJobsAsFailed() {
        for (attempt in heartbeatRegistry.findExpiredAttempts()) {
            val updated = jobRepository.failIfProcessing(attempt.jobId, attempt.attemptNo, Instant.now())
            if (updated == 1) {
                log.info("heartbeat 만료로 FAILED 전이: jobId={}, attemptNo={}", attempt.jobId, attempt.attemptNo)
            }
            heartbeatRegistry.removeHeartbeat(attempt.jobId, attempt.attemptNo)
        }
    }

    private fun markStalledJobsAsFailed() {
        val cutoff = Instant.now().minusSeconds(appProperties.processing.timeoutSeconds)
        val stalled = jobRepository.findByStatusAndUpdatedAtBeforeOrderByIdAsc(
            JobStatus.PROCESSING, cutoff, PageRequest.of(0, SCAN_BATCH_SIZE)
        )
        for (job in stalled) {
            val jobId = job.persistedId
            if (heartbeatRegistry.hasLiveHeartbeat(jobId, job.attemptNo)) {
                continue
            }
            val updated = jobRepository.failIfProcessing(jobId, job.attemptNo, Instant.now())
            if (updated == 1) {
                heartbeatRegistry.removeHeartbeat(jobId, job.attemptNo)
                log.info("PROCESSING 정체 job 회수, FAILED 전이: jobId={}, attemptNo={}", jobId, job.attemptNo)
            }
        }
    }

    private fun retryOrRefundFailedJobs() {
        val failed: List<Job> = jobRepository.findByStatusOrderByIdAsc(
            JobStatus.FAILED, PageRequest.of(0, SCAN_BATCH_SIZE)
        )
        for (job in failed) {
            if (job.attemptNo + 1 < appProperties.generation.maxAttempts) {
                jobLifecycleService.retry(job)
            } else {
                jobLifecycleService.finalRefund(job)
            }
        }
    }

    companion object {
        private const val SCAN_BATCH_SIZE = 100
    }
}
