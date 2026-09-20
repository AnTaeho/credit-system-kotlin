package com.example.credit_system_kotlin.job.repository

import com.example.credit_system_kotlin.job.domain.Job
import com.example.credit_system_kotlin.job.domain.JobStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.data.domain.PageRequest
import org.springframework.test.context.ActiveProfiles
import java.time.Instant

@ActiveProfiles("test")
@DataJpaTest
class JobRepositoryTest @Autowired constructor(
    private val jobRepository: JobRepository
) {

    @Test
    fun `대기 작업은 attemptNo가 일치하면 처리 상태로 전이된다`() {
        val job = jobRepository.save(Job.hold(1L, 100L, "cat"))

        val updated = jobRepository.startProcessingIfAttemptMatches(job.persistedId, 0, Instant.now())

        assertThat(updated).isEqualTo(1)
        assertThat(jobRepository.findById(job.persistedId).orElseThrow().status)
            .isEqualTo(JobStatus.PROCESSING)
    }

    @Test
    fun `attemptNo가 불일치하면 0행이며 상태가 유지된다`() {
        val job = jobRepository.save(Job.hold(1L, 100L, "cat"))

        val updated = jobRepository.startProcessingIfAttemptMatches(job.persistedId, 5, Instant.now())

        assertThat(updated).isZero()
        assertThat(jobRepository.findById(job.persistedId).orElseThrow().status)
            .isEqualTo(JobStatus.HOLDING)
    }

    @Test
    fun `재시도에서 미리 PROCESSING된 작업도 같은 attemptNo면 처리할 수 있다`() {
        val job = jobRepository.save(Job.hold(1L, 100L, "cat"))
        jobRepository.transitionIfStatusAndAttemptMatch(
            job.persistedId, JobStatus.FAILED, JobStatus.HOLDING, 0, Instant.now()
        )
        jobRepository.incrementAttemptForRetry(job.persistedId, 0, Instant.now(), Instant.now())

        val updated = jobRepository.startProcessingIfAttemptMatches(job.persistedId, 1, Instant.now())

        assertThat(updated).isEqualTo(1)
        assertThat(jobRepository.findById(job.persistedId).orElseThrow().status)
            .isEqualTo(JobStatus.PROCESSING)
    }

    @Test
    fun `완료전이는 resultUrl을 함께 기록한다`() {
        val job = jobRepository.save(Job.hold(1L, 100L, "cat"))
        jobRepository.startProcessingIfAttemptMatches(job.persistedId, 0, Instant.now())

        val updated = jobRepository.completeIfAttemptMatches(
            job.persistedId, "https://stub/image/1.png", 0, Instant.now()
        )

        val found = jobRepository.findById(job.persistedId).orElseThrow()
        assertThat(updated).isEqualTo(1)
        assertThat(found.status).isEqualTo(JobStatus.COMPLETED)
        assertThat(found.resultUrl).isEqualTo("https://stub/image/1.png")
    }

    @Test
    fun `환불된 작업은 같은 attemptNo로 완료할 수 없다`() {
        val job = jobRepository.save(Job.hold(1L, 100L, "cat"))
        jobRepository.transitionIfStatusAndAttemptMatch(
            job.persistedId, JobStatus.FAILED, JobStatus.HOLDING, 0, Instant.now()
        )
        jobRepository.transitionIfStatusAndAttemptMatch(
            job.persistedId, JobStatus.REFUNDED, JobStatus.FAILED, 0, Instant.now()
        )

        val updated = jobRepository.completeIfAttemptMatches(
            job.persistedId, "https://stub/image/late.png", 0, Instant.now()
        )

        val found = jobRepository.findById(job.persistedId).orElseThrow()
        assertThat(updated).isZero()
        assertThat(found.status).isEqualTo(JobStatus.REFUNDED)
        assertThat(found.resultUrl).isNull()
    }

    @Test
    fun `종결된 작업은 같은 attemptNo로 처리를 다시 시작할 수 없다`() {
        val job = jobRepository.save(Job.hold(1L, 100L, "cat"))
        jobRepository.startProcessingIfAttemptMatches(job.persistedId, 0, Instant.now())
        jobRepository.completeIfAttemptMatches(
            job.persistedId, "https://stub/image/done.png", 0, Instant.now()
        )

        val updated = jobRepository.startProcessingIfAttemptMatches(job.persistedId, 0, Instant.now())

        assertThat(updated).isZero()
        assertThat(jobRepository.findById(job.persistedId).orElseThrow().status)
            .isEqualTo(JobStatus.COMPLETED)
    }

    @Test
    fun `상태와 attemptNo가 모두 일치할 때만 전이된다`() {
        val job = jobRepository.save(Job.hold(1L, 100L, "cat"))
        jobRepository.transitionIfStatusAndAttemptMatch(
            job.persistedId, JobStatus.FAILED, JobStatus.HOLDING, 0, Instant.now()
        )

        val wrongStatus = jobRepository.transitionIfStatusAndAttemptMatch(
            job.persistedId, JobStatus.REFUNDED, JobStatus.COMPLETED, 0, Instant.now()
        )
        val match = jobRepository.transitionIfStatusAndAttemptMatch(
            job.persistedId, JobStatus.REFUNDED, JobStatus.FAILED, 0, Instant.now()
        )

        assertThat(wrongStatus).isZero()
        assertThat(match).isEqualTo(1)
        assertThat(jobRepository.findById(job.persistedId).orElseThrow().status)
            .isEqualTo(JobStatus.REFUNDED)
    }

    @Test
    fun `재시도 투입은 FAILED 상태에서만 attemptNo를 증가시킨다`() {
        val job = jobRepository.save(Job.hold(1L, 100L, "cat"))

        val beforeFail = jobRepository.incrementAttemptForRetry(job.persistedId, 0, Instant.now(), Instant.now())
        assertThat(beforeFail).isZero()

        jobRepository.transitionIfStatusAndAttemptMatch(
            job.persistedId, JobStatus.FAILED, JobStatus.HOLDING, 0, Instant.now()
        )
        val afterFail = jobRepository.incrementAttemptForRetry(job.persistedId, 0, Instant.now(), Instant.now())

        val found = jobRepository.findById(job.persistedId).orElseThrow()
        assertThat(afterFail).isEqualTo(1)
        assertThat(found.status).isEqualTo(JobStatus.HOLDING)
        assertThat(found.attemptNo).isEqualTo(1)
    }

    /**
     * 디스패처가 보는 조회다. 재시도 대기(`nextAttemptAt`)가 남은 job 은 시각이 지나기 전에는
     * 집히지 않고, 지나면 집힌다. 최초 접수는 `nextAttemptAt` 이 없어 예전처럼 즉시 집힌다.
     */
    @Test
    fun `대기 시각이 지나지 않은 job은 집히지 않고 지나면 집힌다`() {
        val now = Instant.parse("2026-09-20T00:00:00Z")
        val waiting = jobRepository.save(Job.hold(1L, 100L, "cat"))
        jobRepository.transitionIfStatusAndAttemptMatch(
            waiting.persistedId, JobStatus.FAILED, JobStatus.HOLDING, 0, now
        )
        jobRepository.incrementAttemptForRetry(waiting.persistedId, 0, now.plusSeconds(10), now)

        val beforeDue = jobRepository.findDispatchableByStatus(JobStatus.HOLDING, now, PageRequest.of(0, 10))
        val atDue = jobRepository.findDispatchableByStatus(
            JobStatus.HOLDING, now.plusSeconds(10), PageRequest.of(0, 10)
        )

        assertThat(beforeDue).extracting<Long> { it.persistedId }.doesNotContain(waiting.persistedId)
        assertThat(atDue).extracting<Long> { it.persistedId }.contains(waiting.persistedId)
    }

    /** 최초 접수는 `nextAttemptAt` 이 NULL 이다. backoff 가 생겨도 즉시 집혀야 한다(회귀). */
    @Test
    fun `최초 접수는 대기 시각이 없어 즉시 집힌다`() {
        val fresh = jobRepository.save(Job.hold(1L, 100L, "cat"))

        val found = jobRepository.findDispatchableByStatus(
            JobStatus.HOLDING, Instant.parse("2026-09-20T00:00:00Z"), PageRequest.of(0, 10)
        )

        assertThat(fresh.nextAttemptAt).isNull()
        assertThat(found).extracting<Long> { it.persistedId }.contains(fresh.persistedId)
    }

    @Test
    fun `updatedAt이 cutoff 이전인 HOLDING job만 조회된다`() {
        val job = jobRepository.save(Job.hold(1L, 100L, "cat"))
        val staleUpdatedAt = Instant.now().minusSeconds(120)
        jobRepository.transitionIfStatusAndAttemptMatch(
            job.persistedId, JobStatus.HOLDING, JobStatus.HOLDING, 0, staleUpdatedAt
        )

        val caught = jobRepository.findByStatusAndUpdatedAtBeforeOrderByIdAsc(
            JobStatus.HOLDING, Instant.now(), PageRequest.of(0, 10)
        )
        val notCaught = jobRepository.findByStatusAndUpdatedAtBeforeOrderByIdAsc(
            JobStatus.HOLDING, Instant.now().minusSeconds(300), PageRequest.of(0, 10)
        )

        assertThat(caught.map { it.persistedId }).containsExactly(job.persistedId)
        assertThat(notCaught).isEmpty()
    }

    @Test
    fun `failIfProcessing은 PROCESSING인 job만 FAILED로 내린다`() {
        val processingJob = jobRepository.save(Job.hold(1L, 100L, "cat"))
        jobRepository.startProcessingIfAttemptMatches(processingJob.persistedId, 0, Instant.now())
        val holdingJob = jobRepository.save(Job.hold(1L, 100L, "dog"))

        val fromProcessing = jobRepository.failIfProcessing(processingJob.persistedId, 0, Instant.now())
        val fromHolding = jobRepository.failIfProcessing(holdingJob.persistedId, 0, Instant.now())

        assertThat(fromProcessing).isEqualTo(1)
        assertThat(jobRepository.findById(processingJob.persistedId).orElseThrow().status)
            .isEqualTo(JobStatus.FAILED)
        assertThat(fromHolding).isZero()
        assertThat(jobRepository.findById(holdingJob.persistedId).orElseThrow().status)
            .isEqualTo(JobStatus.HOLDING)
    }

    @Test
    fun `rollbackToHoldingIfProcessing은 PROCESSING인 job만 HOLDING으로 되돌린다`() {
        val processingJob = jobRepository.save(Job.hold(1L, 100L, "cat"))
        jobRepository.startProcessingIfAttemptMatches(processingJob.persistedId, 0, Instant.now())
        val holdingJob = jobRepository.save(Job.hold(1L, 100L, "dog"))

        val fromProcessing = jobRepository.rollbackToHoldingIfProcessing(processingJob.persistedId, 0, Instant.now())
        val fromHolding = jobRepository.rollbackToHoldingIfProcessing(holdingJob.persistedId, 0, Instant.now())

        assertThat(fromProcessing).isEqualTo(1)
        assertThat(jobRepository.findById(processingJob.persistedId).orElseThrow().status)
            .isEqualTo(JobStatus.HOLDING)
        assertThat(fromHolding).isZero()
        assertThat(jobRepository.findById(holdingJob.persistedId).orElseThrow().status)
            .isEqualTo(JobStatus.HOLDING)
    }

    @Test
    fun `refundIfFailed는 FAILED인 job만 REFUNDED로 내린다`() {
        val failedJob = jobRepository.save(Job.hold(1L, 100L, "cat"))
        jobRepository.transitionIfStatusAndAttemptMatch(
            failedJob.persistedId, JobStatus.FAILED, JobStatus.HOLDING, 0, Instant.now()
        )
        val holdingJob = jobRepository.save(Job.hold(1L, 100L, "dog"))

        val fromFailed = jobRepository.refundIfFailed(failedJob.persistedId, 0, Instant.now())
        val fromHolding = jobRepository.refundIfFailed(holdingJob.persistedId, 0, Instant.now())

        assertThat(fromFailed).isEqualTo(1)
        assertThat(jobRepository.findById(failedJob.persistedId).orElseThrow().status)
            .isEqualTo(JobStatus.REFUNDED)
        assertThat(fromHolding).isZero()
        assertThat(jobRepository.findById(holdingJob.persistedId).orElseThrow().status)
            .isEqualTo(JobStatus.HOLDING)
    }
}
