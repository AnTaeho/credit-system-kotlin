package com.example.credit_system_kotlin.job.worker

import com.example.credit_system_kotlin.global.config.WorkerProperties
import com.example.credit_system_kotlin.job.domain.Job
import com.example.credit_system_kotlin.job.domain.JobStatus
import com.example.credit_system_kotlin.job.repository.JobRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.core.task.TaskExecutor
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant

private val log = LoggerFactory.getLogger(GenerationWorker::class.java)

@Component
@ConditionalOnExpression("\${app.scheduling.enabled:true} and \${app.worker.enabled:true}")
class GenerationWorker(
    private val jobRepository: JobRepository,
    private val jobProcessor: GenerationJobProcessor,
    @Qualifier("generationWorkerExecutor") private val workerExecutor: TaskExecutor,
    workerProperties: WorkerProperties,
    @Value("\${app.scheduling.worker-interval-millis:500}") pollIntervalMillis: Long
) {

    private val batchSize: Int = workerProperties.batchSize

    init {
        val concurrency = workerProperties.concurrency
        val maxDispatchPerCycle = minOf(batchSize, concurrency)
        val throughputCapPerSecond = maxDispatchPerCycle / (pollIntervalMillis / 1000.0)
        log.info(
            "워커 처리량 상한: batch-size={}, concurrency={}, 폴링 주기={}ms -> 주기당 최대 {}건, 초당 최대 {}건",
            batchSize, concurrency, pollIntervalMillis, maxDispatchPerCycle, throughputCapPerSecond
        )
        if (batchSize < concurrency) {
            log.warn(
                "worker batch-size({})가 concurrency({})보다 작아 한 폴링 주기에 executor 슬롯을 " +
                        "전부 채우지 못합니다. job 처리 시간이 폴링 주기({}ms)보다 충분히 길면 문제되지 않지만, " +
                        "짧아지면 남는 슬롯만큼 스레드가 놀게 됩니다.",
                batchSize, concurrency, pollIntervalMillis
            )
        }
    }

    @Scheduled(fixedDelayString = "\${app.scheduling.worker-interval-millis:500}")
    fun dispatchPendingJobs() {
        val jobs = jobRepository.findByStatusOrderByIdAsc(JobStatus.HOLDING, PageRequest.of(0, batchSize))
        for (job in jobs) {
            if (!claim(job)) {
                continue
            }
            if (!dispatch(job)) {
                return
            }
        }
    }

    private fun claim(job: Job): Boolean {
        return try {
            val updated = jobRepository.startProcessingIfAttemptMatches(
                requireNotNull(job.id), job.attemptNo, Instant.now()
            )
            if (updated == 0) {
                log.info("다른 워커가 선점했거나 무효한 작업 무시: jobId={}, attemptNo={}", job.id, job.attemptNo)
                false
            } else {
                true
            }
        } catch (e: RuntimeException) {
            log.warn("생성 작업 선점 실패: jobId={}, attemptNo={}", job.id, job.attemptNo, e)
            false
        }
    }

    private fun dispatch(job: Job): Boolean {
        return try {
            workerExecutor.execute { jobProcessor.runGeneration(job) }
            true
        } catch (e: RuntimeException) {
            log.warn(
                "생성 작업 executor 위임 실패, 이번 주기 중단: jobId={}, attemptNo={}",
                job.id, job.attemptNo, e
            )
            rollbackToHolding(job)
            false
        }
    }

    private fun rollbackToHolding(job: Job) {
        try {
            jobRepository.transitionIfStatusAndAttemptMatch(
                requireNotNull(job.id), JobStatus.HOLDING, JobStatus.PROCESSING, job.attemptNo, Instant.now()
            )
        } catch (e: RuntimeException) {
            log.error("선점 롤백 실패, timeout 회수 대기: jobId={}, attemptNo={}", job.id, job.attemptNo, e)
        }
    }
}
