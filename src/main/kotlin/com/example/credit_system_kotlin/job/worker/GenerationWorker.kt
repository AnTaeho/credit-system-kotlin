package com.example.credit_system_kotlin.job.worker

import com.example.credit_system_kotlin.global.config.WorkerProperties
import com.example.credit_system_kotlin.job.domain.Job
import com.example.credit_system_kotlin.job.domain.JobStatus
import com.example.credit_system_kotlin.job.repository.JobRepository
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
            if (!claim(job)) continue
            workerExecutor.execute { jobProcessor.runGeneration(job) }
        }
    }

    private fun claim(job: Job): Boolean {
        val updated = jobRepository.startProcessingIfAttemptMatches(
            job.persistedId, job.attemptNo, Instant.now()
        )
        if (updated == 0) {
            log.info("다른 워커가 선점했거나 무효한 작업 무시: jobId={}, attemptNo={}", job.persistedId, job.attemptNo)
            return false
        }
        return true
    }
}
