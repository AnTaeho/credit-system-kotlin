package com.example.credit_system_kotlin.job.worker

import com.example.credit_system_kotlin.heartbeat.HeartbeatRegistry
import com.example.credit_system_kotlin.job.domain.Job
import com.example.credit_system_kotlin.job.service.JobLifecycleService
import com.example.credit_system_kotlin.job.stub.GenerationStubClient
import com.example.credit_system_kotlin.job.stub.StubGenerationException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

private val log = LoggerFactory.getLogger(GenerationJobProcessor::class.java)

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
            confirm(job, resultUrl)
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
        } catch (e: RuntimeException) {
            log.error("생성 중 예기치 못한 예외 발생: jobId={}, attemptNo={}", job.persistedId, job.attemptNo, e)
            jobLifecycleService.markFailed(job.persistedId, job.attemptNo)
            null
        }

    /** 결과 반영에 실패하면 job 은 PROCESSING 으로 남아 정체 회수 대상이 된다. */
    private fun confirm(job: Job, resultUrl: String) {
        try {
            jobLifecycleService.confirm(job, resultUrl)
        } catch (e: RuntimeException) {
            log.error(
                "생성 결과 반영 실패, timeout 회수 대기: jobId={}, attemptNo={}",
                job.persistedId, job.attemptNo, e
            )
        }
    }
}
