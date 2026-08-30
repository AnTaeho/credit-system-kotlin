package com.example.credit_system_kotlin.job.service

import com.example.credit_system_kotlin.global.exception.InsufficientBalanceException
import com.example.credit_system_kotlin.job.domain.Job
import com.example.credit_system_kotlin.job.domain.JobStatus
import com.example.credit_system_kotlin.job.repository.IdempotencyKeyRepository
import com.example.credit_system_kotlin.job.repository.JobRepository
import com.example.credit_system_kotlin.ledger.repository.LedgerRepository
import com.example.credit_system_kotlin.organization.domain.Organization
import com.example.credit_system_kotlin.organization.repository.OrganizationRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.time.Instant

@ActiveProfiles("test")
@SpringBootTest
class ServiceTransactionRollbackTest @Autowired constructor(
    private val holdService: HoldService,
    private val jobLifecycleService: JobLifecycleService,
    private val idempotencyKeyRepository: IdempotencyKeyRepository,
    private val jobRepository: JobRepository,
    private val ledgerRepository: LedgerRepository,
    private val organizationRepository: OrganizationRepository
) {

    @AfterEach
    fun tearDown() {
        ledgerRepository.deleteAll()
        idempotencyKeyRepository.deleteAll()
        jobRepository.deleteAll()
        organizationRepository.deleteAll()
    }

    @Test
    fun `잔액 부족으로 hold가 실패하면 선점한 멱등 키도 롤백된다`() {
        val organization = organizationRepository.save(Organization("poor", 50L))

        assertThatThrownBy {
            holdService.requestGeneration(organization.persistedId, "rollback-key", "cat")
        }
            .isInstanceOf(InsufficientBalanceException::class.java)

        assertThat(
            idempotencyKeyRepository.findByOrganizationIdAndIdemKey(
                organization.persistedId, "rollback-key"
            )
        ).isNull()
        assertThat(jobRepository.findByOrganizationIdOrderByIdDesc(organization.persistedId)).isEmpty()
        assertThat(ledgerRepository.findByOrganizationIdOrderByIdDesc(organization.persistedId)).isEmpty()
        assertThat(organizationRepository.findById(organization.persistedId).orElseThrow().balance)
            .isEqualTo(50L)
    }

    @Test
    fun `환불할 조직이 사라졌으면 REFUNDED 전이도 롤백된다`() {
        val organization = organizationRepository.save(Organization("deleted", 0L))
        val job = jobRepository.save(Job.hold(organization.persistedId, 100L, "cat"))
        jobRepository.transitionIfStatusAndAttemptMatch(
            job.persistedId, JobStatus.FAILED, JobStatus.HOLDING, 0, Instant.now()
        )
        val failed = jobRepository.findById(job.persistedId).orElseThrow()
        organizationRepository.deleteById(organization.persistedId)

        assertThatThrownBy { jobLifecycleService.finalRefund(failed) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("환불 잔액 반영 실패")

        assertThat(jobRepository.findById(job.persistedId).orElseThrow().status)
            .isEqualTo(JobStatus.FAILED)
        assertThat(ledgerRepository.findByOrganizationIdOrderByIdDesc(organization.persistedId)).isEmpty()
    }
}
