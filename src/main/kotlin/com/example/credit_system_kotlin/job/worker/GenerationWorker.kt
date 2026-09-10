package com.example.credit_system_kotlin.job.worker

import com.example.credit_system_kotlin.global.config.WorkerProperties
import com.example.credit_system_kotlin.global.event.DefenseOutcome
import com.example.credit_system_kotlin.global.event.DefensePoint
import com.example.credit_system_kotlin.global.event.DefenseTriggered
import com.example.credit_system_kotlin.job.domain.Job
import com.example.credit_system_kotlin.job.domain.JobStatus
import com.example.credit_system_kotlin.job.repository.JobRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.context.ApplicationEventPublisher
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
    private val eventPublisher: ApplicationEventPublisher,
    private val workerSlots: WorkerSlots,
    private val drainGate: WorkerDrainGate,
    workerProperties: WorkerProperties
) {

    private val batchSize: Int = workerProperties.batchSize

    /**
     * 빈 슬롯 수만큼만 읽고 선점한다.
     *
     * 예전에는 `batchSize` 만큼 읽어 전부 선점한 뒤 executor 에 밀어 넣었다. 풀이 꽉 차 있으면
     * `execute` 가 거부하고 [rollbackToHolding] 이 되돌리는데, 다음 폴링에서 같은 job 을 다시
     * 선점하므로 포화 구간 내내 "선점 → 거부 → 롤백" 이 매 주기 반복됐다. DB UPDATE 두 번이
     * 헛돌고, 그 헛선점이 전부 `worker_claim/applied` 로 세어져 카운터를 처리량으로 읽을 수 없었다.
     *
     * **경쟁 조건이 없는 이유.** 이 executor 에 task 를 넣는 스레드는 `@Scheduled` 디스패처
     * 하나뿐이다(`fixedDelay` 라 이전 실행이 끝나야 다음이 잡히므로 디스패처가 둘 겹치지도 않는다).
     * 다른 스레드는 task 를 끝내면서 `activeCount` 를 **줄이기만** 한다. 따라서 주기 시작에 읽은
     * `free` 는 그 주기 동안 과소평가일 수는 있어도 과대평가일 수 없고, 한 주기에 `free` 개
     * 이하만 넘기면 `maxPoolSize` 를 절대 넘지 않는다. 새 스레드 기동 직후 `activeCount` 가 잠깐
     * 낮게 읽히는 창이 있더라도, 우리가 제한하는 것은 "이번 주기에 우리가 넘긴 수" 자체다.
     */
    @Scheduled(fixedDelayString = "\${app.scheduling.worker-interval-millis:500}")
    fun dispatchPendingJobs() {
        // 배포 드레인이 시작되면 문이 닫혀 이 주기 전체가 통째로 건너뛰어진다. 조회도 선점도 없다.
        drainGate.runIfOpen { dispatchCycle() }
    }

    private fun dispatchCycle() {
        val free = workerSlots.free()
        if (free <= 0) {
            // 넘길 곳이 없으면 조회조차 하지 않는다. 포화 구간의 폴링 비용까지 없앤다.
            return
        }
        val jobs = jobRepository.findByStatusOrderByIdAsc(
            JobStatus.HOLDING, PageRequest.of(0, minOf(batchSize, free))
        )
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
                eventPublisher.publishEvent(DefenseTriggered(DefensePoint.WORKER_CLAIM, DefenseOutcome.LOST))
                false
            } else {
                eventPublisher.publishEvent(DefenseTriggered(DefensePoint.WORKER_CLAIM, DefenseOutcome.APPLIED))
                true
            }
        } catch (e: RuntimeException) {
            log.warn("생성 작업 선점 실패: jobId={}, attemptNo={}", job.id, job.attemptNo, e)
            false
        }
    }

    /**
     * 거부 → 롤백은 **안전망으로만 남겼다.** 슬롯을 세고 넘기므로 정상 경로에서는 여기가 타지 않고,
     * executor shutdown 중 거부 같은 예외 상황만 남는다. 그래서 이 경로가 실제로 타면
     * "슬롯 계산이 틀렸다"는 신호이고, 그걸 [DefenseOutcome.ROLLED_BACK] 으로 드러낸다.
     */
    private fun dispatch(job: Job): Boolean {
        return try {
            workerExecutor.execute { jobProcessor.runGeneration(job) }
            true
        } catch (e: RuntimeException) {
            log.warn(
                "생성 작업 executor 위임 실패, 이번 주기 중단: jobId={}, attemptNo={}",
                job.id, job.attemptNo, e
            )
            eventPublisher.publishEvent(DefenseTriggered(DefensePoint.WORKER_CLAIM, DefenseOutcome.ROLLED_BACK))
            rollbackToHolding(job)
            false
        }
    }

    private fun rollbackToHolding(job: Job) {
        try {
            jobRepository.rollbackToHoldingIfProcessing(job.persistedId, job.attemptNo, Instant.now())
        } catch (e: RuntimeException) {
            log.error("선점 롤백 실패, timeout 회수 대기: jobId={}, attemptNo={}", job.id, job.attemptNo, e)
        }
    }
}
