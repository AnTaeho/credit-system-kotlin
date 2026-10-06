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

/** HOLDING job 을 주기적으로 집어 워커 풀에 넘긴다. 실제 생성은 [GenerationJobProcessor] 가 한다. */
@Component
@ConditionalOnExpression("\${app.scheduling.enabled:true} and \${app.worker.enabled:true}")
class GenerationWorker(
    private val jobRepository: JobRepository,
    private val jobProcessor: GenerationJobProcessor,
    @Qualifier("generationWorkerExecutor") private val workerExecutor: TaskExecutor,
    private val eventPublisher: ApplicationEventPublisher,
    private val workerSlots: WorkerSlots,
    workerProperties: WorkerProperties
) {

    private val batchSize: Int = workerProperties.batchSize

    /**
     * 빈 슬롯 수만큼만 선점한다. 풀에 task 를 넣는 스레드는 이 디스패처 하나고 나머지는 끝내면서 슬롯을 비우기만 한다.
     * 그래서 주기 시작에 읽은 빈 슬롯 수는 실제보다 적을 수는 있어도 많을 수 없고, 풀이 넘치지 않는다.
     */
    @Scheduled(fixedDelayString = "\${app.scheduling.worker-interval-millis:500}")
    fun dispatchPendingJobs() {
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

    /** HOLDING 에서 PROCESSING 으로 올리는 데 성공해야 true 다. 0행이거나 DB 예외면 그 job 만 건너뛴다. */
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

    /** 슬롯을 세고 넘기므로 거부는 executor 종료 중 같은 예외 상황에서만 난다. 그때는 선점을 되돌리고 이번 주기를 끝낸다. */
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

    /** 되돌리기까지 실패하면 job 은 PROCESSING 으로 남고 정체 회수가 가져간다. */
    private fun rollbackToHolding(job: Job) {
        try {
            jobRepository.rollbackToHoldingIfProcessing(job.persistedId, job.attemptNo, Instant.now())
        } catch (e: RuntimeException) {
            log.error("선점 롤백 실패, timeout 회수 대기: jobId={}, attemptNo={}", job.id, job.attemptNo, e)
        }
    }
}
