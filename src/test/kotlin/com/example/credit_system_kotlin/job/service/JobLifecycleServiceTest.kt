package com.example.credit_system_kotlin.job.service

import com.example.credit_system_kotlin.job.domain.Job
import com.example.credit_system_kotlin.job.domain.JobRepository
import com.example.credit_system_kotlin.job.domain.JobStatus
import com.example.credit_system_kotlin.ledger.LedgerRepository
import com.example.credit_system_kotlin.ledger.LedgerType
import com.example.credit_system_kotlin.organization.Organization
import com.example.credit_system_kotlin.organization.OrganizationRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.util.ReflectionTestUtils
import java.time.Instant

@ActiveProfiles("test")
@DataJpaTest
class JobLifecycleServiceTest @Autowired constructor(
    private val jobRepository: JobRepository,
    private val organizationRepository: OrganizationRepository,
    private val ledgerRepository: LedgerRepository
) {

    private val jobLifecycleService =
        JobLifecycleService(jobRepository, organizationRepository, ledgerRepository)

    @Test
    fun `attemptNo가 일치하면 완료 처리되고 ledger가 남는다`() {
        val job = jobRepository.save(Job.hold(1L, 100L, "cat"))
        jobRepository.startProcessingIfAttemptMatches(job.persistedId, 0, Instant.now())

        jobLifecycleService.confirm(job, "https://stub/x.png")

        val found = jobRepository.findById(job.persistedId).orElseThrow()
        assertThat(found.status).isEqualTo(JobStatus.COMPLETED)
        assertThat(found.resultUrl).isEqualTo("https://stub/x.png")
        assertThat(ledgerRepository.findByOrganizationIdOrderByIdDesc(1L)).hasSize(1)
    }

    @Test
    fun `attemptNo가 불일치하면 아무것도 하지 않는다`() {
        val job = jobRepository.save(Job.hold(1L, 100L, "cat"))
        jobRepository.startProcessingIfAttemptMatches(job.persistedId, 0, Instant.now())
        ReflectionTestUtils.setField(job, "attemptNo", 5)

        jobLifecycleService.confirm(job, "https://stub/x.png")

        val found = jobRepository.findById(job.persistedId).orElseThrow()
        assertThat(found.status).isEqualTo(JobStatus.PROCESSING)
        assertThat(ledgerRepository.findByOrganizationIdOrderByIdDesc(1L)).isEmpty()
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
        assertThat(ledgerRepository.findByOrganizationIdOrderByIdDesc(1L)).isEmpty()
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
        assertThat(ledgerRepository.findByOrganizationIdOrderByIdDesc(1L)).hasSize(1)
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

    @Test
    fun `FAILED 상태가 아니면 아무것도 하지 않는다`() {
        val job = jobRepository.save(Job.hold(1L, 100L, "cat"))

        jobLifecycleService.retry(job)

        val found = jobRepository.findById(job.persistedId).orElseThrow()
        assertThat(found.attemptNo).isZero()
    }

    @Test
    fun `FAILED job은 REFUNDED로 전이되고 잔액이 복구된다`() {
        val organization = organizationRepository.save(Organization("acme", 700L))
        val job = jobRepository.save(Job.hold(organization.persistedId, 300L, "cat"))
        jobRepository.transitionIfStatusAndAttemptMatch(
            job.persistedId, JobStatus.FAILED, JobStatus.HOLDING, 0, Instant.now()
        )

        jobLifecycleService.finalRefund(jobRepository.findById(job.persistedId).orElseThrow())

        val foundJob = jobRepository.findById(job.persistedId).orElseThrow()
        val foundOrg = organizationRepository.findById(organization.persistedId).orElseThrow()
        assertThat(foundJob.status).isEqualTo(JobStatus.REFUNDED)
        assertThat(foundOrg.balance).isEqualTo(1000L)
        assertThat(ledgerRepository.findByOrganizationIdOrderByIdDesc(organization.persistedId))
            .anyMatch { it.type == LedgerType.REFUND }
    }

    @Test
    fun `FAILED 상태가 아니면 환불하지 않는다`() {
        val organization = organizationRepository.save(Organization("acme", 700L))
        val job = jobRepository.save(Job.hold(organization.persistedId, 300L, "cat"))

        jobLifecycleService.finalRefund(job)

        val foundOrg = organizationRepository.findById(organization.persistedId).orElseThrow()
        assertThat(foundOrg.balance).isEqualTo(700L)
    }

    @Test
    fun `같은 작업을 두 번 환불해도 잔액과 원장은 한 번만 반영된다`() {
        val organization = organizationRepository.save(Organization("acme", 700L))
        val job = jobRepository.save(Job.hold(organization.persistedId, 300L, "cat"))
        jobRepository.transitionIfStatusAndAttemptMatch(
            job.persistedId, JobStatus.FAILED, JobStatus.HOLDING, 0, Instant.now()
        )
        val failed = jobRepository.findById(job.persistedId).orElseThrow()

        jobLifecycleService.finalRefund(failed)
        jobLifecycleService.finalRefund(failed)

        assertThat(organizationRepository.findById(organization.persistedId).orElseThrow().balance)
            .isEqualTo(1000L)
        assertThat(ledgerRepository.findByOrganizationIdOrderByIdDesc(organization.persistedId))
            .hasSize(1)
    }
}
