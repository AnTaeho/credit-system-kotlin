package com.example.credit_system_kotlin.job.service

import com.example.credit_system_kotlin.global.config.appProperties
import com.example.credit_system_kotlin.global.event.DefenseOutcome
import com.example.credit_system_kotlin.global.event.DefensePoint
import com.example.credit_system_kotlin.job.domain.Job
import com.example.credit_system_kotlin.job.domain.JobStatus
import com.example.credit_system_kotlin.job.repository.JobRepository
import com.example.credit_system_kotlin.ledger.domain.LedgerType
import com.example.credit_system_kotlin.ledger.repository.LedgerRepository
import com.example.credit_system_kotlin.support.FixedMutableClock
import com.example.credit_system_kotlin.support.RecordingEventPublisher
import com.example.credit_system_kotlin.user.domain.User
import com.example.credit_system_kotlin.user.repository.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.util.ReflectionTestUtils
import java.time.Duration
import java.time.Instant

@ActiveProfiles("test")
@DataJpaTest
class JobLifecycleServiceTest @Autowired constructor(
    private val jobRepository: JobRepository,
    private val userRepository: UserRepository,
    private val ledgerRepository: LedgerRepository
) {

    private val eventPublisher = RecordingEventPublisher()

    /** backoff 기본값(10초 × 4배, 상한 300초)을 그대로 쓴다. 시각은 밀 수 있는 시계로 고정한다. */
    private val clock = FixedMutableClock(NOW)

    private val jobLifecycleService = JobLifecycleService(
        jobRepository, userRepository, ledgerRepository, eventPublisher, appProperties(), clock
    )

    @Test
    fun `attemptNo가 일치하면 완료 처리되고 ledger가 남는다`() {
        val job = jobRepository.save(Job.hold(1L, 100L, "cat"))
        jobRepository.startProcessingIfAttemptMatches(job.persistedId, 0, Instant.now())

        jobLifecycleService.confirm(job, "https://stub/x.png")

        val found = jobRepository.findById(job.persistedId).orElseThrow()
        assertThat(found.status).isEqualTo(JobStatus.COMPLETED)
        assertThat(found.resultUrl).isEqualTo("https://stub/x.png")
        assertThat(ledgerRepository.findByUserIdOrderByIdDesc(1L)).hasSize(1)
    }

    @Test
    fun `attemptNo가 불일치하면 아무것도 하지 않는다`() {
        val job = jobRepository.save(Job.hold(1L, 100L, "cat"))
        jobRepository.startProcessingIfAttemptMatches(job.persistedId, 0, Instant.now())
        ReflectionTestUtils.setField(job, "attemptNo", 5)

        jobLifecycleService.confirm(job, "https://stub/x.png")

        val found = jobRepository.findById(job.persistedId).orElseThrow()
        assertThat(found.status).isEqualTo(JobStatus.PROCESSING)
        assertThat(ledgerRepository.findByUserIdOrderByIdDesc(1L)).isEmpty()
    }

    @Test
    fun `환불된 작업의 늦은 confirm은 무시한다`() {
        val job = jobRepository.save(Job.hold(1L, 100L, "cat"))
        jobRepository.transitionIfStatusAndAttemptMatch(
            job.persistedId, JobStatus.FAILED, JobStatus.HOLDING, 0, Instant.now()
        )
        jobRepository.transitionIfStatusAndAttemptMatch(
            job.persistedId, JobStatus.REFUNDED, JobStatus.FAILED, 0, Instant.now()
        )

        jobLifecycleService.confirm(job, "https://stub/late.png")

        val found = jobRepository.findById(job.persistedId).orElseThrow()
        assertThat(found.status).isEqualTo(JobStatus.REFUNDED)
        assertThat(found.resultUrl).isNull()
        assertThat(ledgerRepository.findByUserIdOrderByIdDesc(1L)).isEmpty()
    }

    @Test
    fun `같은 attempt의 confirm을 두 번 호출해도 원장은 한 번만 기록된다`() {
        val job = jobRepository.save(Job.hold(1L, 100L, "cat"))
        jobRepository.startProcessingIfAttemptMatches(job.persistedId, 0, Instant.now())

        jobLifecycleService.confirm(job, "https://stub/first.png")
        jobLifecycleService.confirm(job, "https://stub/second.png")

        val found = jobRepository.findById(job.persistedId).orElseThrow()
        assertThat(found.status).isEqualTo(JobStatus.COMPLETED)
        assertThat(found.resultUrl).isEqualTo("https://stub/first.png")
        assertThat(ledgerRepository.findByUserIdOrderByIdDesc(1L)).hasSize(1)
    }

    @Test
    fun `attemptNo가 일치하면 FAILED로 전이한다`() {
        val job = jobRepository.save(Job.hold(1L, 100L, "cat"))
        jobRepository.startProcessingIfAttemptMatches(job.persistedId, 0, Instant.now())

        jobLifecycleService.markFailed(job.persistedId, 0)

        assertThat(jobRepository.findById(job.persistedId).orElseThrow().status)
            .isEqualTo(JobStatus.FAILED)
    }

    @Test
    fun `attemptNo가 불일치하면 전이하지 않는다`() {
        val job = jobRepository.save(Job.hold(1L, 100L, "cat"))
        jobRepository.startProcessingIfAttemptMatches(job.persistedId, 0, Instant.now())

        jobLifecycleService.markFailed(job.persistedId, 9)

        assertThat(jobRepository.findById(job.persistedId).orElseThrow().status)
            .isEqualTo(JobStatus.PROCESSING)
    }

    @Test
    fun `완료된 작업의 늦은 실패는 무시한다`() {
        val job = jobRepository.save(Job.hold(1L, 100L, "cat"))
        jobRepository.startProcessingIfAttemptMatches(job.persistedId, 0, Instant.now())
        jobRepository.completeIfAttemptMatches(
            job.persistedId, "https://stub/done.png", 0, Instant.now()
        )

        jobLifecycleService.markFailed(job.persistedId, 0)

        assertThat(jobRepository.findById(job.persistedId).orElseThrow().status)
            .isEqualTo(JobStatus.COMPLETED)
    }

    @Test
    fun `FAILED 상태의 job은 attemptNo가 증가하고 다시 대기한다`() {
        val job = jobRepository.save(Job.hold(1L, 100L, "cat"))
        jobRepository.transitionIfStatusAndAttemptMatch(
            job.persistedId, JobStatus.FAILED, JobStatus.HOLDING, 0, Instant.now()
        )

        jobLifecycleService.retry(jobRepository.findById(job.persistedId).orElseThrow())

        val found = jobRepository.findById(job.persistedId).orElseThrow()
        assertThat(found.status).isEqualTo(JobStatus.HOLDING)
        assertThat(found.attemptNo).isEqualTo(1)
    }

    /**
     * 재시도는 바로 다시 잡히면 안 된다. 첫 재시도는 base(10초), 두 번째는 base × multiplier(40초)
     * 뒤부터 가능해야 한다 — 잠깐 문제면 빨리, 오래가면 덜 자주 두드린다.
     */
    @Test
    fun `첫 재시도는 10초 뒤, 그다음은 40초 뒤부터 가능하다`() {
        val job = jobRepository.save(Job.hold(1L, 100L, "cat"))
        failCurrentAttempt(job.persistedId, 0)

        jobLifecycleService.retry(jobRepository.findById(job.persistedId).orElseThrow())

        assertThat(jobRepository.findById(job.persistedId).orElseThrow().nextAttemptAt)
            .isEqualTo(NOW.plusSeconds(10))

        clock.advance(Duration.ofSeconds(60))
        failCurrentAttempt(job.persistedId, 1)

        jobLifecycleService.retry(jobRepository.findById(job.persistedId).orElseThrow())

        val found = jobRepository.findById(job.persistedId).orElseThrow()
        assertThat(found.attemptNo).isEqualTo(2)
        assertThat(found.nextAttemptAt).isEqualTo(NOW.plusSeconds(60).plusSeconds(40))
    }

    /** FAILED 에서 다시 실패 상태로 되돌려, 다음 재시도를 만들 수 있게 한다. */
    private fun failCurrentAttempt(jobId: Long, attemptNo: Int) {
        jobRepository.transitionIfStatusAndAttemptMatch(
            jobId, JobStatus.FAILED, JobStatus.HOLDING, attemptNo, clock.instant()
        )
    }

    @Test
    fun `FAILED 상태가 아니면 아무것도 하지 않는다`() {
        val job = jobRepository.save(Job.hold(1L, 100L, "cat"))

        jobLifecycleService.retry(job)

        val found = jobRepository.findById(job.persistedId).orElseThrow()
        assertThat(found.attemptNo).isZero()
    }

    @Test
    fun `FAILED job은 REFUNDED로 전이되고 잔액이 복구된다`() {
        val user = userRepository.save(User("acme", 700L))
        val job = jobRepository.save(Job.hold(user.persistedId, 300L, "cat"))
        jobRepository.transitionIfStatusAndAttemptMatch(
            job.persistedId, JobStatus.FAILED, JobStatus.HOLDING, 0, Instant.now()
        )

        jobLifecycleService.finalRefund(jobRepository.findById(job.persistedId).orElseThrow())

        val foundJob = jobRepository.findById(job.persistedId).orElseThrow()
        val foundUser = userRepository.findById(user.persistedId).orElseThrow()
        assertThat(foundJob.status).isEqualTo(JobStatus.REFUNDED)
        assertThat(foundUser.balance).isEqualTo(1000L)
        assertThat(ledgerRepository.findByUserIdOrderByIdDesc(user.persistedId))
            .anyMatch { it.type == LedgerType.REFUND }
    }

    @Test
    fun `FAILED 상태가 아니면 환불하지 않는다`() {
        val user = userRepository.save(User("acme", 700L))
        val job = jobRepository.save(Job.hold(user.persistedId, 300L, "cat"))

        jobLifecycleService.finalRefund(job)

        val foundUser = userRepository.findById(user.persistedId).orElseThrow()
        assertThat(foundUser.balance).isEqualTo(700L)
    }

    @Test
    fun `같은 작업을 두 번 환불해도 잔액과 원장은 한 번만 반영된다`() {
        val user = userRepository.save(User("acme", 700L))
        val job = jobRepository.save(Job.hold(user.persistedId, 300L, "cat"))
        jobRepository.transitionIfStatusAndAttemptMatch(
            job.persistedId, JobStatus.FAILED, JobStatus.HOLDING, 0, Instant.now()
        )
        val failed = jobRepository.findById(job.persistedId).orElseThrow()

        jobLifecycleService.finalRefund(failed)
        jobLifecycleService.finalRefund(failed)

        assertThat(userRepository.findById(user.persistedId).orElseThrow().balance)
            .isEqualTo(1000L)
        assertThat(ledgerRepository.findByUserIdOrderByIdDesc(user.persistedId))
            .hasSize(1)
    }

    @Test
    fun `attemptNo가 일치하는 confirm은 CONFIRM APPLIED를 발행한다`() {
        val job = jobRepository.save(Job.hold(1L, 100L, "cat"))
        jobRepository.startProcessingIfAttemptMatches(job.persistedId, 0, Instant.now())

        jobLifecycleService.confirm(job, "https://stub/x.png")

        assertThat(eventPublisher.countOf(DefensePoint.CONFIRM, DefenseOutcome.APPLIED)).isEqualTo(1)
        assertThat(eventPublisher.countOf(DefensePoint.CONFIRM, DefenseOutcome.STALE)).isZero()
    }

    @Test
    fun `attemptNo가 어긋난 confirm은 CONFIRM STALE을 발행한다`() {
        val job = jobRepository.save(Job.hold(1L, 100L, "cat"))
        jobRepository.startProcessingIfAttemptMatches(job.persistedId, 0, Instant.now())
        ReflectionTestUtils.setField(job, "attemptNo", 5)

        jobLifecycleService.confirm(job, "https://stub/x.png")

        assertThat(eventPublisher.countOf(DefensePoint.CONFIRM, DefenseOutcome.STALE)).isEqualTo(1)
        assertThat(eventPublisher.countOf(DefensePoint.CONFIRM, DefenseOutcome.APPLIED)).isZero()
    }

    @Test
    fun `이미 완료된 job의 늦은 실패 처리는 MARK_FAILED STALE을 발행한다`() {
        val job = jobRepository.save(Job.hold(1L, 100L, "cat"))
        jobRepository.startProcessingIfAttemptMatches(job.persistedId, 0, Instant.now())
        jobRepository.completeIfAttemptMatches(job.persistedId, "https://stub/done.png", 0, Instant.now())

        jobLifecycleService.markFailed(job.persistedId, 0)

        assertThat(eventPublisher.countOf(DefensePoint.MARK_FAILED, DefenseOutcome.STALE)).isEqualTo(1)
        assertThat(eventPublisher.countOf(DefensePoint.MARK_FAILED, DefenseOutcome.APPLIED)).isZero()
    }

    @Test
    fun `재시도 투입에 밀리면 RETRY_CLAIM LOST를 발행한다`() {
        val job = jobRepository.save(Job.hold(1L, 100L, "cat"))
        jobRepository.transitionIfStatusAndAttemptMatch(
            job.persistedId, JobStatus.FAILED, JobStatus.HOLDING, 0, Instant.now()
        )
        val failed = jobRepository.findById(job.persistedId).orElseThrow()

        jobLifecycleService.retry(failed)
        jobLifecycleService.retry(failed)

        assertThat(eventPublisher.countOf(DefensePoint.RETRY_CLAIM, DefenseOutcome.APPLIED)).isEqualTo(1)
        assertThat(eventPublisher.countOf(DefensePoint.RETRY_CLAIM, DefenseOutcome.LOST)).isEqualTo(1)
    }

    @Test
    fun `이미 처리된 job의 최종 환불은 FINAL_REFUND RACED를 발행한다`() {
        val user = userRepository.save(User("acme", 700L))
        val job = jobRepository.save(Job.hold(user.persistedId, 300L, "cat"))
        jobRepository.transitionIfStatusAndAttemptMatch(
            job.persistedId, JobStatus.FAILED, JobStatus.HOLDING, 0, Instant.now()
        )
        val failed = jobRepository.findById(job.persistedId).orElseThrow()

        jobLifecycleService.finalRefund(failed)
        jobLifecycleService.finalRefund(failed)

        assertThat(eventPublisher.countOf(DefensePoint.FINAL_REFUND, DefenseOutcome.APPLIED)).isEqualTo(1)
        assertThat(eventPublisher.countOf(DefensePoint.FINAL_REFUND, DefenseOutcome.RACED)).isEqualTo(1)
    }

    companion object {
        private val NOW: Instant = Instant.parse("2026-09-20T00:00:00Z")
    }
}
