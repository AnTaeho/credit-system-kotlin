package com.example.credit_system_kotlin.job.worker

import com.example.credit_system_kotlin.global.config.WorkerProperties
import com.example.credit_system_kotlin.job.domain.JobStatus
import com.example.credit_system_kotlin.job.repository.JobRepository
import com.example.credit_system_kotlin.job.service.JobLifecycleService
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
@ConditionalOnExpression("\${app.scheduling.enabled:true} and \${app.worker.enabled:true}")
class GenerationWorker(
    private val jobRepository: JobRepository,
    private val jobProcessor: GenerationJobProcessor,
    private val jobLifecycleService: JobLifecycleService,
    workerProperties: WorkerProperties
) {

    private val batchSize: Int = workerProperties.batchSize

    @Scheduled(fixedDelayString = "\${app.scheduling.worker-interval-millis:500}")
    fun dispatchPendingJobs() {
        val jobs = jobRepository.findByStatusOrderByIdAsc(JobStatus.HOLDING, PageRequest.of(0, batchSize))
        for (job in jobs) {
            jobLifecycleService.startProcessing(job.persistedId)
            jobProcessor.runGeneration(job)
        }
    }
}
