package com.example.credit_system_kotlin.job.worker

import com.example.credit_system_kotlin.global.WorkerProperties
import com.example.credit_system_kotlin.job.domain.Job
import com.example.credit_system_kotlin.job.domain.JobRepository
import com.example.credit_system_kotlin.job.domain.JobStatus
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
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
    workerProperties: WorkerProperties
) {

    private val batchSize: Int = workerProperties.batchSize

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
                job.persistedId, job.attemptNo, Instant.now()
            )
            if (updated == 0) {
                log.info("다른 워커가 선점했거나 무효한 작업 무시: jobId={}, attemptNo={}", job.persistedId, job.attemptNo)
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
                job.persistedId, JobStatus.HOLDING, JobStatus.PROCESSING, job.attemptNo, Instant.now()
            )
        } catch (e: RuntimeException) {
            log.error("선점 롤백 실패, timeout 회수 대기: jobId={}, attemptNo={}", job.id, job.attemptNo, e)
        }
    }
}
