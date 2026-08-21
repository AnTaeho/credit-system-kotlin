package com.example.credit_system_kotlin.job.worker

import com.example.credit_system_kotlin.global.exception.StubGenerationException
import com.example.credit_system_kotlin.job.domain.Job
import com.example.credit_system_kotlin.job.service.JobLifecycleService
import com.example.credit_system_kotlin.job.stub.GenerationStubClient
import com.example.credit_system_kotlin.scheduler.HeartbeatRegistry
import org.slf4j.LoggerFactory
import org.springframework.dao.RecoverableDataAccessException
import org.springframework.dao.TransientDataAccessException
import org.springframework.stereotype.Component
import org.springframework.transaction.CannotCreateTransactionException

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
            confirmWithRetry(job, resultUrl)
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

    private fun confirmWithRetry(job: Job, resultUrl: String) {
        for (attempt in 1..CONFIRM_MAX_ATTEMPTS) {
            try {
                jobLifecycleService.confirm(job, resultUrl)
                return
            } catch (e: RuntimeException) {
                val retryable = isRetryable(e)
                if (!retryable || attempt == CONFIRM_MAX_ATTEMPTS) {
                    log.error(
                        "생성 결과 반영 실패({}), timeout 회수 대기: jobId={}, attemptNo={}, 시도={}/{}",
                        if (retryable) "재시도 소진" else "재시도 대상 아닌 예외",
                        job.persistedId, job.attemptNo, attempt, CONFIRM_MAX_ATTEMPTS, e
                    )
                    return
                }
                log.warn(
                    "생성 결과 반영 실패, 재시도: jobId={}, attemptNo={}, 시도={}/{}",
                    job.persistedId, job.attemptNo, attempt, CONFIRM_MAX_ATTEMPTS, e
                )
            }
            if (!awaitBeforeRetry()) {
                log.error(
                    "생성 결과 반영 재시도 대기 중 인터럽트, timeout 회수 대기: jobId={}, attemptNo={}",
                    job.persistedId, job.attemptNo
                )
                return
            }
        }
    }

    /** 재시도 전 대기. 인터럽트되면 플래그를 복원하고 false를 돌려 재시도를 멈춘다. */
    private fun awaitBeforeRetry(): Boolean =
        try {
            Thread.sleep(CONFIRM_RETRY_DELAY_MILLIS)
            true
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }

    private fun isRetryable(e: RuntimeException): Boolean =
        e is TransientDataAccessException ||
            e is RecoverableDataAccessException ||
            e is CannotCreateTransactionException

    companion object {
        private const val CONFIRM_MAX_ATTEMPTS = 3
        private const val CONFIRM_RETRY_DELAY_MILLIS = 200L
    }
}
