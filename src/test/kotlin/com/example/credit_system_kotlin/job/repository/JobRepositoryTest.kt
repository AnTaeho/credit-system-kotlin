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
        jobRepository.incrementAttemptForRetry(job.persistedId, 0, Instant.now())

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

        val beforeFail = jobRepository.incrementAttemptForRetry(job.persistedId, 0, Instant.now())
        assertThat(beforeFail).isZero()

        jobRepository.transitionIfStatusAndAttemptMatch(
            job.persistedId, JobStatus.FAILED, JobStatus.HOLDING, 0, Instant.now()
        )
        val afterFail = jobRepository.incrementAttemptForRetry(job.persistedId, 0, Instant.now())

        val found = jobRepository.findById(job.persistedId).orElseThrow()
        assertThat(afterFail).isEqualTo(1)
        assertThat(found.status).isEqualTo(JobStatus.HOLDING)
        assertThat(found.attemptNo).isEqualTo(1)
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
