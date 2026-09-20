package com.example.credit_system_kotlin.job.worker

import com.example.credit_system_kotlin.heartbeat.HeartbeatRegistry
import com.example.credit_system_kotlin.job.domain.Job
import com.example.credit_system_kotlin.job.generation.GenerationClient
import com.example.credit_system_kotlin.job.generation.GenerationException
import com.example.credit_system_kotlin.job.generation.GenerationTimeoutException
import com.example.credit_system_kotlin.job.service.JobLifecycleService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

private val log = LoggerFactory.getLogger(GenerationJobProcessor::class.java)

@Component
class GenerationJobProcessor(
    private val heartbeatRegistry: HeartbeatRegistry,
    private val generationClient: GenerationClient,
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

    /**
     * 생성에 성공하면 resultUrl, 실패하면 FAILED로 기록하고 null.
     *
     * **타임아웃도 생성 실패와 같은 경로다.** 돈이 묶이지 않게 하려면 "외부가 언젠가 답한다"를
     * 기다리는 것이 아니라 실패로 확정해 회수·재시도에 태워야 한다. 다만 로그에서는 구분한다.
     * 실패율이 올라간 것과 외부가 느려진 것은 대응이 다른 사건이기 때문이다.
     */
    private fun generateOrMarkFailed(job: Job): String? =
        try {
            generationClient.generate(job.prompt)
        } catch (e: GenerationException) {
            if (e is GenerationTimeoutException) {
                log.warn(
                    "생성 타임아웃, 실패 처리: jobId={}, attemptNo={}, message={}",
                    job.persistedId, job.attemptNo, e.message
                )
            }
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
