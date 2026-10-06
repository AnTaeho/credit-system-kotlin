package com.example.credit_system_kotlin.job.scheduling

import com.example.credit_system_kotlin.global.config.AppProperties
import com.example.credit_system_kotlin.heartbeat.HeartbeatRegistry
import com.example.credit_system_kotlin.heartbeat.HeartbeatState
import com.example.credit_system_kotlin.heartbeat.JobAttempt
import com.example.credit_system_kotlin.job.domain.Job
import com.example.credit_system_kotlin.job.domain.JobStatus
import com.example.credit_system_kotlin.job.event.JobRecovered
import com.example.credit_system_kotlin.job.event.RecoveryDetector
import com.example.credit_system_kotlin.job.repository.JobRepository
import com.example.credit_system_kotlin.job.service.JobLifecycleService
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant

private val log = LoggerFactory.getLogger(DeadJobRecoveryTask::class.java)

/** 죽은 워커가 붙잡고 있던 job 을 주기적으로 회수하고, FAILED 로 모인 job 을 재시도하거나 환불한다. */
@Component
@ConditionalOnProperty(prefix = "app.scheduling", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class DeadJobRecoveryTask(
    private val heartbeatRegistry: HeartbeatRegistry,
    private val jobRepository: JobRepository,
    private val jobLifecycleService: JobLifecycleService,
    private val appProperties: AppProperties,
    private val eventPublisher: ApplicationEventPublisher
) {

    /** 세 단계를 순서대로 돌린다. 단계마다 따로 잡아서 앞 단계가 터져도 뒤 단계는 돈다. */
    @Scheduled(fixedDelayString = $$"${app.scheduling.dead-job-scan-interval-millis:5000}")
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

    /** heartbeat 가 끊긴 시도를 FAILED 로 돌린다. 재시도·환불은 같은 주기의 마지막 단계가 한다. */
    private fun markExpiredJobsAsFailed() {
        for (attempt in heartbeatRegistry.findExpiredAttempts()) {
            recoverExpired(attempt)
        }
    }

    /** 한 건의 실패가 같은 주기의 나머지를 막지 않도록 항목 단위로 격리한다. */
    private fun recoverExpired(attempt: JobAttempt) {
        try {
            val updated = jobRepository.failIfProcessing(attempt.jobId, attempt.attemptNo, Instant.now())
            if (updated == 1) {
                log.info("heartbeat 만료로 FAILED 전이: jobId={}, attemptNo={}", attempt.jobId, attempt.attemptNo)
                eventPublisher.publishEvent(
                    JobRecovered(attempt.jobId, attempt.attemptNo, RecoveryDetector.HEARTBEAT)
                )
            }
            heartbeatRegistry.removeHeartbeat(attempt.jobId, attempt.attemptNo)
        } catch (e: RuntimeException) {
            log.warn("heartbeat 만료 job 회수 실패: jobId={}, attemptNo={}", attempt.jobId, attempt.attemptNo, e)
        }
    }

    /** heartbeat 스캔이 놓친 것을 잡는다. PROCESSING 인 채 timeout 넘게 updatedAt 이 그대로인 job 을 100건까지 본다. */
    private fun markStalledJobsAsFailed() {
        val cutoff = Instant.now().minusSeconds(appProperties.processing.timeoutSeconds)
        val stalled = jobRepository.findByStatusAndUpdatedAtBeforeOrderByIdAsc(
            JobStatus.PROCESSING, cutoff, PageRequest.of(0, SCAN_BATCH_SIZE)
        )
        for (job in stalled) {
            recoverStalled(job)
        }
    }

    /**
     * heartbeat 를 못 본 경우([HeartbeatState.UNKNOWN])에도 회수한다. Redis 가 죽었다고 멈추면 돈이 묶인 채 남는다.
     * 살아 있는 job 을 잘못 내려도 원래 워커의 confirm 은 attemptNo 조건에서 0행이라 돈은 안 틀어진다.
     */
    private fun recoverStalled(job: Job) {
        try {
            val jobId = job.persistedId
            val state = heartbeatRegistry.heartbeatState(jobId, job.attemptNo)
            if (state == HeartbeatState.LIVE) {
                return
            }
            if (state == HeartbeatState.UNKNOWN) {
                log.warn(
                    "heartbeat 저장소에 닿지 않아 updatedAt 만으로 회수: jobId={}, attemptNo={}",
                    jobId, job.attemptNo
                )
            }
            val updated = jobRepository.failIfProcessing(jobId, job.attemptNo, Instant.now())
            if (updated == 1) {
                log.info("PROCESSING 정체 job 회수, FAILED 전이: jobId={}, attemptNo={}", jobId, job.attemptNo)
                eventPublisher.publishEvent(JobRecovered(jobId, job.attemptNo, detectorFor(state)))
                heartbeatRegistry.removeHeartbeat(jobId, job.attemptNo)
            }
        } catch (e: RuntimeException) {
            log.warn("PROCESSING 정체 job 회수 실패: jobId={}, attemptNo={}", job.id, job.attemptNo, e)
        }
    }

    /** heartbeat 를 못 보고 내린 회수는 오탐일 수 있어 지표에서 따로 센다. */
    private fun detectorFor(state: HeartbeatState): RecoveryDetector = when (state) {
        HeartbeatState.UNKNOWN -> RecoveryDetector.BACKSTOP_BLIND
        else -> RecoveryDetector.BACKSTOP
    }

    /** FAILED job 을 id 순으로 100건까지 읽어 한 건씩 재시도나 환불로 넘긴다. 앞 단계에서 방금 내린 것도 여기서 잡힌다. */
    private fun retryOrRefundFailedJobs() {
        val failed: List<Job> = jobRepository.findByStatusOrderByIdAsc(
            JobStatus.FAILED, PageRequest.of(0, SCAN_BATCH_SIZE)
        )
        for (job in failed) {
            retryOrRefund(job)
        }
    }

    /** 한 건의 실패가 같은 주기의 나머지를 막지 않도록 항목 단위로 격리한다. */
    private fun retryOrRefund(job: Job) {
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

    companion object {
        private const val SCAN_BATCH_SIZE = 100
    }
}
