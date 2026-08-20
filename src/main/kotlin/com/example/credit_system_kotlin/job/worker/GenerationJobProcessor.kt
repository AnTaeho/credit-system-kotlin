package com.example.credit_system_kotlin.job.worker

import com.example.credit_system_kotlin.global.exception.StubGenerationException
import com.example.credit_system_kotlin.job.domain.Job
import com.example.credit_system_kotlin.job.service.JobLifecycleService
import com.example.credit_system_kotlin.job.stub.GenerationStubClient
import com.example.credit_system_kotlin.scheduler.HeartbeatRegistry
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
        val jobId = requireNotNull(job.id) { "저장되지 않은 job은 실행할 수 없습니다." }
        val attemptNo = job.attemptNo
        val heartbeatFuture = heartbeatRegistry.startHeartbeat(jobId, attemptNo)
        try {
            val resultUrl = try {
                stubClient.generate(job.prompt)
            } catch (e: StubGenerationException) {
                jobLifecycleService.markFailed(jobId, job.attemptNo)
                return
            } catch (e: RuntimeException) {
                log.error("생성 중 예기치 못한 예외 발생: jobId={}, attemptNo={}", jobId, job.attemptNo, e)
                jobLifecycleService.markFailed(jobId, job.attemptNo)
                return
            }

            try {
                confirmWithRetry(job, resultUrl)
            } catch (e: RuntimeException) {
                log.error(
                    "생성 결과 반영 재시도 소진, timeout 회수 대기: jobId={}, attemptNo={}",
                    jobId, job.attemptNo, e
                )
            }
        } finally {
            heartbeatRegistry.stopHeartbeat(jobId, attemptNo, heartbeatFuture)
        }
    }

    private fun confirmWithRetry(job: Job, resultUrl: String) {
        var lastFailure: RuntimeException? = null
        for (attempt in 1..CONFIRM_MAX_ATTEMPTS) {
            try {
                jobLifecycleService.confirm(job, resultUrl)
                return
            } catch (e: RuntimeException) {
                lastFailure = e
                log.warn(
                    "생성 결과 반영 실패: jobId={}, attemptNo={}, 시도={}/{}",
                    job.id, job.attemptNo, attempt, CONFIRM_MAX_ATTEMPTS, e
                )
            }
            if (attempt < CONFIRM_MAX_ATTEMPTS) {
                try {
                    Thread.sleep(CONFIRM_RETRY_DELAY_MILLIS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }
        throw lastFailure ?: IllegalStateException("confirm 재시도가 한 번도 수행되지 않았습니다: jobId=${job.id}")
    }

    companion object {
        private const val CONFIRM_MAX_ATTEMPTS = 3
        private const val CONFIRM_RETRY_DELAY_MILLIS = 200L
    }
}
