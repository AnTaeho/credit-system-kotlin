package com.example.credit_system_kotlin.scheduler

import com.example.credit_system_kotlin.global.config.AppProperties
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

private val log = LoggerFactory.getLogger(DeadJobSchedulerTask::class.java)

@Component
@ConditionalOnProperty(prefix = "app.scheduling", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class DeadJobSchedulerTask(
    private val heartbeatRegistry: HeartbeatRegistry,
    private val jobRepository: JobRepository,
    private val jobLifecycleService: JobLifecycleService,
    private val appProperties: AppProperties
) {

    @Scheduled(fixedDelayString = "\${app.scheduling.dead-job-scan-interval-millis:5000}")
    fun scan() {
        try {
            markExpiredJobsAsFailed()
        } catch (e: RuntimeException) {
            log.error("heartbeat 만료 회수 단계 실패, 이번 주기 건너뜀", e)
        }
        try {
            markStalledJobsAsFailed()
        } catch (e: RuntimeException) {
            log.error("PROCESSING 정체 회수 단계 실패, 이번 주기 건너뜀", e)
        }
        try {
            retryOrRefundFailedJobs()
        } catch (e: RuntimeException) {
            log.error("FAILED job 재검토 단계 실패, 이번 주기 건너뜀", e)
        }
    }

    private fun markExpiredJobsAsFailed() {
        for (attempt in heartbeatRegistry.findExpiredAttempts()) {
            try {
                val updated = jobRepository.transitionIfStatusAndAttemptMatch(
                    attempt.jobId, JobStatus.FAILED, JobStatus.PROCESSING, attempt.attemptNo, Instant.now()
                )
                if (updated == 1) {
                    log.info("heartbeat 만료로 FAILED 전이: jobId={}, attemptNo={}", attempt.jobId, attempt.attemptNo)
                }
                heartbeatRegistry.removeHeartbeat(attempt.jobId, attempt.attemptNo)
            } catch (e: RuntimeException) {
                log.warn("heartbeat 만료 job 회수 실패: jobId={}, attemptNo={}", attempt.jobId, attempt.attemptNo, e)
            }
        }
    }

    private fun markStalledJobsAsFailed() {
        val cutoff = Instant.now().minusSeconds(appProperties.processing.timeoutSeconds)
        val stalled = jobRepository.findByStatusAndUpdatedAtBeforeOrderByIdAsc(
            JobStatus.PROCESSING, cutoff, PageRequest.of(0, SCAN_BATCH_SIZE)
        )
        for (job in stalled) {
            try {
                val jobId = requireNotNull(job.id) { "저장되지 않은 job이 조회되었습니다." }
                if (heartbeatRegistry.hasLiveHeartbeat(jobId, job.attemptNo)) {
                    continue
                }
                val updated = jobRepository.transitionIfStatusAndAttemptMatch(
                    jobId, JobStatus.FAILED, JobStatus.PROCESSING, job.attemptNo, Instant.now()
                )
                if (updated == 1) {
                    heartbeatRegistry.removeHeartbeat(jobId, job.attemptNo)
                    log.info("PROCESSING 정체 job 회수, FAILED 전이: jobId={}, attemptNo={}", jobId, job.attemptNo)
                }
            } catch (e: RuntimeException) {
                log.warn("PROCESSING 정체 job 회수 실패: jobId={}, attemptNo={}", job.id, job.attemptNo, e)
            }
        }
    }

    private fun retryOrRefundFailedJobs() {
        val failed: List<Job> = jobRepository.findByStatusOrderByIdAsc(
            JobStatus.FAILED, PageRequest.of(0, SCAN_BATCH_SIZE)
        )
        for (job in failed) {
            try {
                if (job.attemptNo + 1 < appProperties.generation.maxAttempts) {
                    jobLifecycleService.retry(job)
                } else {
                    jobLifecycleService.finalRefund(job)
                }
            } catch (e: RuntimeException) {
                log.warn("FAILED job 재검토 실패: jobId={}, attemptNo={}", job.id, job.attemptNo, e)
            }
        }
    }

    companion object {
        private const val SCAN_BATCH_SIZE = 100
    }
}
