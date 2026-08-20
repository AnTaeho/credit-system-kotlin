package com.example.credit_system_kotlin.job.concurrency

import com.example.credit_system_kotlin.job.domain.JobStatus
import com.example.credit_system_kotlin.job.repository.JobRepository
import com.example.credit_system_kotlin.job.service.HoldService
import com.example.credit_system_kotlin.ledger.repository.LedgerRepository
import com.example.credit_system_kotlin.organization.domain.Organization
import com.example.credit_system_kotlin.organization.repository.OrganizationRepository
import com.example.credit_system_kotlin.support.persistedId
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.util.concurrent.TimeUnit

@ActiveProfiles("test")
@SpringBootTest(
    properties = [
        "app.scheduling.enabled=true",
        "app.worker.enabled=true",
        "app.stub.failure-rate=1.0",
        "app.scheduling.worker-interval-millis=100",
        "app.scheduling.dead-job-scan-interval-millis=500"
    ]
)
class RetryRefundTest @Autowired constructor(
    private val holdService: HoldService,
    private val jobRepository: JobRepository,
    private val organizationRepository: OrganizationRepository,
    private val ledgerRepository: LedgerRepository
) {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            SharedContainers.registerDatabase(registry, "retry_refund")
        }
    }

    @Test
    fun `매번 실패하면 재시도를 모두 소진하고 최종적으로 환불된다`() {
        val organization = organizationRepository.save(Organization("acme", 1000L))

        val result = holdService.requestGeneration(organization.persistedId, "retry-key", "cat")

        await().atMost(30, TimeUnit.SECONDS).untilAsserted {
            val job = jobRepository.findById(result.jobId).orElseThrow()
            assertThat(job.status).isEqualTo(JobStatus.REFUNDED)
            assertThat(job.attemptNo).isEqualTo(2)
        }

        val found = organizationRepository.findById(organization.persistedId).orElseThrow()
        assertThat(found.balance).isEqualTo(1000L)
        assertThat(ledgerRepository.findByOrganizationIdOrderByIdDesc(organization.persistedId))
            .extracting<String> { it.type.name }
            .contains("HOLD", "REFUND")
    }
}
