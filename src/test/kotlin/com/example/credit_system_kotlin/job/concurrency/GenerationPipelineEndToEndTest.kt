package com.example.credit_system_kotlin.job.concurrency

import com.example.credit_system_kotlin.job.domain.JobRepository
import com.example.credit_system_kotlin.job.domain.JobStatus
import com.example.credit_system_kotlin.job.service.HoldService
import com.example.credit_system_kotlin.ledger.LedgerRepository
import com.example.credit_system_kotlin.organization.Organization
import com.example.credit_system_kotlin.organization.OrganizationRepository
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
        "app.stub.failure-rate=0.0",
        "app.scheduling.worker-interval-millis=100"
    ]
)
class GenerationPipelineEndToEndTest @Autowired constructor(
    private val holdService: HoldService,
    private val jobRepository: JobRepository,
    private val ledgerRepository: LedgerRepository,
    private val organizationRepository: OrganizationRepository
) {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            SharedContainers.registerDatabase(registry, "pipeline_e2e")
        }
    }

    @Test
    fun `hold 요청부터 컨펌까지 전체 파이프라인이 실제로 동작한다`() {
        val organization = organizationRepository.save(Organization("acme", 1000L))

        val result = holdService.requestGeneration(
            organization.persistedId, "e2e-key", "a cat wearing sunglasses"
        )

        await().atMost(20, TimeUnit.SECONDS).untilAsserted {
            val job = jobRepository.findById(result.jobId).orElseThrow()
            assertThat(job.status).isEqualTo(JobStatus.COMPLETED)
            assertThat(job.resultUrl).isNotNull()
        }

        val found = organizationRepository.findById(organization.persistedId).orElseThrow()
        assertThat(found.balance).isEqualTo(900L)
        assertThat(ledgerRepository.findByOrganizationIdOrderByIdDesc(organization.persistedId))
            .extracting<String> { it.type.name }
            .contains("HOLD", "CONFIRM")
    }
}
