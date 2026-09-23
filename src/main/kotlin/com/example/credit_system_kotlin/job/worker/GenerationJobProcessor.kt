package com.example.credit_system_kotlin.job.worker

import com.example.credit_system_kotlin.global.logging.withJobLogContext
import com.example.credit_system_kotlin.heartbeat.HeartbeatRegistry
import com.example.credit_system_kotlin.job.domain.Job
import com.example.credit_system_kotlin.job.event.ExternalGenerationCalled
import com.example.credit_system_kotlin.job.generation.GenerationClient
import com.example.credit_system_kotlin.job.generation.GenerationException
import com.example.credit_system_kotlin.job.generation.GenerationTimeoutException
import com.example.credit_system_kotlin.job.service.JobLifecycleService
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component

private val log = LoggerFactory.getLogger(GenerationJobProcessor::class.java)

@Component
class GenerationJobProcessor(
    private val heartbeatRegistry: HeartbeatRegistry,
    private val generationClient: GenerationClient,
    private val jobLifecycleService: JobLifecycleService,
    private val eventPublisher: ApplicationEventPublisher
) {

    /**
     * 이 메서드 전체를 [withJobLogContext] 로 감싼다. 여기가 HTTP 밖으로 넘어온 첫 지점이라
     * 요청 ID 는 없고, 대신 jobId·attemptNo 가 이 스레드의 로그를 묶는 식별자가 된다.
     * 워커 스레드는 풀에서 재사용되므로 정리를 빠뜨리면 다음 job 의 로그가 이 job 의 것으로 보인다.
     */
    fun runGeneration(job: Job) {
        val jobId = job.persistedId
        val attemptNo = job.attemptNo
        withJobLogContext(jobId, attemptNo) {
            val heartbeatFuture = heartbeatRegistry.startHeartbeat(jobId, attemptNo)
            try {
                val resultUrl = generateOrMarkFailed(job) ?: return@withJobLogContext
                confirm(job, resultUrl)
            } finally {
                heartbeatRegistry.stopHeartbeat(jobId, attemptNo, heartbeatFuture)
            }
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
            // INV-04b 계측. 호출 **직전**에 발행한다 — 즉시 던지는 호출도 외부로는 나갔을 수 있고,
            // 무엇보다 타임아웃된 호출이야말로 중복 원가의 본체이기 때문이다.
            // 스텁은 prompt 만 받아 jobId 를 모르고 시그니처도 바꾸지 않기로 했으므로
            // (2026-09-23 확정, `git show req-v3:docs/02-design.md` 1-2), 세는 자리는 jobId·attemptNo 가 있는 여기다.
            eventPublisher.publishEvent(ExternalGenerationCalled(job.persistedId, job.attemptNo))
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
