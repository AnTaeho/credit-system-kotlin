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

@Component
@ConditionalOnProperty(prefix = "app.scheduling", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class DeadJobRecoveryTask(
    private val heartbeatRegistry: HeartbeatRegistry,
    private val jobRepository: JobRepository,
    private val jobLifecycleService: JobLifecycleService,
    private val appProperties: AppProperties,
    private val eventPublisher: ApplicationEventPublisher
) {

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

    private fun markStalledJobsAsFailed() {
        val now = Instant.now()
        val cutoff = now.minusSeconds(appProperties.processing.timeoutSeconds)
        val hardCapCutoff = now.minusSeconds(appProperties.processing.absoluteTimeoutSeconds)
        val stalled = jobRepository.findByStatusAndUpdatedAtBeforeOrderByIdAsc(
            JobStatus.PROCESSING, cutoff, PageRequest.of(0, SCAN_BATCH_SIZE)
        )
        for (job in stalled) {
            recoverStalled(job, hardCapCutoff)
        }
    }

    /**
     * 한 건의 실패가 같은 주기의 나머지를 막지 않도록 항목 단위로 격리한다.
     *
     * heartbeat 를 못 본 경우([HeartbeatState.UNKNOWN])에도 회수한다. 이 백스톱의 존재 이유가
     * "heartbeat 저장소가 죽어도 돈이 묶인 채 방치되지 않는다"이므로, 저장소가 안 보인다고
     * 회수를 멈추면 장치가 스스로를 부정한다. 대신 오탐 가능성을 라벨로 남긴다 —
     * 살아 있는 job 을 잘못 내려도 attemptNo CAS 가 돈을 지키고(원래 워커의 confirm 은 0행),
     * 비용은 낭비된 외부 호출 1회다.
     *
     * heartbeat 가 LIVE 여도 [AppProperties.Processing.absoluteTimeoutSeconds] 를 넘겼으면
     * 회수한다([RecoveryDetector.HARD_CAP]). 근거는 아래 [detectorFor] 주석에 있다.
     */
    private fun recoverStalled(job: Job, hardCapCutoff: Instant) {
        try {
            val jobId = job.persistedId
            val state = heartbeatRegistry.heartbeatState(jobId, job.attemptNo)
            val detector = detectorFor(state, job.updatedAt.isBefore(hardCapCutoff), jobId, job.attemptNo)
                ?: return
            val updated = jobRepository.failIfProcessing(jobId, job.attemptNo, Instant.now())
            if (updated == 1) {
                log.info("PROCESSING 정체 job 회수, FAILED 전이: jobId={}, attemptNo={}", jobId, job.attemptNo)
                eventPublisher.publishEvent(JobRecovered(jobId, job.attemptNo, detector))
                // 좀비의 종지기는 여기서 지워도 5초 뒤 같은 멤버를 다시 써넣는다. 워커 스레드가
                // 끝나야 finally 의 stopHeartbeat 가 돌기 때문이다. 즉 ZSET 에 고아 멤버가 남고,
                // 계속 갱신되므로 findExpiredAttempts 에는 잡히지도 않는다. 무해한 이유는
                // HeartbeatRegistry.removeHeartbeat 주석 그대로다 — 언젠가 만료돼 다시 집혀도
                // 그 job 은 이미 PROCESSING 이 아니라 failIfProcessing 이 0행을 돌려준다.
                // 고아 자체를 없애려면 멈춘 스레드를 깨울 수단이 있어야 한다. 이번 조각 밖이다.
                heartbeatRegistry.removeHeartbeat(jobId, job.attemptNo)
            }
        } catch (e: RuntimeException) {
            log.warn("PROCESSING 정체 job 회수 실패: jobId={}, attemptNo={}", job.id, job.attemptNo, e)
        }
    }

    /**
     * 회수할지, 한다면 무엇이 잡은 것으로 셀지 판정한다. `null` 이면 이번 주기에는 건너뛴다.
     *
     * - LIVE + 절대 상한 이내 → 건너뜀. 워커가 살아 있다고 믿는다. 여기가 기본 동작이다.
     * - LIVE + 절대 상한 초과 → [RecoveryDetector.HARD_CAP]. heartbeat 는 "이 프로세스가
     *   살아 있다"만 말할 뿐 워커 스레드가 일하고 있다는 뜻이 아니다. 멈춘 워커의
     *   종지기는 영원히 갱신하므로 이 상한이 없으면 돈이 영구히 묶인다.
     * - ABSENT → [RecoveryDetector.BACKSTOP]. heartbeat 누수 신호.
     * - UNKNOWN → [RecoveryDetector.BACKSTOP_BLIND]. Redis 장애 신호.
     *
     * HARD_CAP 은 **정상인데 아주 느린 job 도 잡는다.** 그 대가를 알고 받아들인다 — 잘못
     * 내려도 attemptNo CAS 가 돈을 지키고(원래 워커의 confirm/markFailed 는 0행 = STALE),
     * 비용은 낭비된 외부 호출 1회다. 반대로 상한이 없으면 대가는 영구히 묶인 돈이다.
     */
    private fun detectorFor(
        state: HeartbeatState,
        pastHardCap: Boolean,
        jobId: Long,
        attemptNo: Int
    ): RecoveryDetector? = when {
        state == HeartbeatState.LIVE && !pastHardCap -> null

        state == HeartbeatState.LIVE -> {
            log.warn(
                "heartbeat 는 LIVE 지만 절대 상한을 넘겨 회수한다(멈춘 워커로 판정). " +
                    "정상인데 느린 job 이었다면 원래 워커의 전이가 0행으로 막히고 외부 호출 1회를 버린다: " +
                    "jobId={}, attemptNo={}, updatedAt 기준 상한={}초",
                jobId, attemptNo, appProperties.processing.absoluteTimeoutSeconds
            )
            RecoveryDetector.HARD_CAP
        }

        state == HeartbeatState.UNKNOWN -> {
            log.warn(
                "heartbeat 저장소에 닿지 않아 updatedAt 만으로 회수: jobId={}, attemptNo={}",
                jobId, attemptNo
            )
            RecoveryDetector.BACKSTOP_BLIND
        }

        else -> RecoveryDetector.BACKSTOP
    }

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
