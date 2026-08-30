package com.example.credit_system_kotlin.job.worker

import com.example.credit_system_kotlin.heartbeat.HeartbeatRegistry
import com.example.credit_system_kotlin.job.domain.Job
import com.example.credit_system_kotlin.job.service.JobLifecycleService
import com.example.credit_system_kotlin.job.stub.GenerationStubClient
import com.example.credit_system_kotlin.job.stub.StubGenerationException
import org.springframework.stereotype.Component

@Component
class GenerationJobProcessor(
    private val heartbeatRegistry: HeartbeatRegistry,
    private val stubClient: GenerationStubClient,
    private val jobLifecycleService: JobLifecycleService
) {

    fun runGeneration(job: Job) {
        val jobId = job.persistedId
        val attemptNo = job.attemptNo
        val heartbeatFuture = heartbeatRegistry.startHeartbeat(jobId, attemptNo)
        try {
            val resultUrl = generateOrMarkFailed(job) ?: return
            jobLifecycleService.confirm(job, resultUrl)
        } finally {
            heartbeatRegistry.stopHeartbeat(jobId, attemptNo, heartbeatFuture)
        }
    }

    /** 생성에 성공하면 resultUrl, 실패하면 FAILED로 기록하고 null */
    private fun generateOrMarkFailed(job: Job): String? =
        try {
            stubClient.generate(job.prompt)
        } catch (_: StubGenerationException) {
            jobLifecycleService.markFailed(job.persistedId, job.attemptNo)
            null
        }
}
