package com.example.credit_system_kotlin.job.concurrency

import com.example.credit_system_kotlin.ledger.domain.LedgerType
import com.example.credit_system_kotlin.ledger.repository.LedgerRepository
import com.example.credit_system_kotlin.organization.domain.Organization
import com.example.credit_system_kotlin.organization.repository.OrganizationRepository
import com.example.credit_system_kotlin.organization.service.OrganizationService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

@ActiveProfiles("test")
@SpringBootTest
class ConcurrentChargeTest @Autowired constructor(
    private val organizationService: OrganizationService,
    private val ledgerRepository: LedgerRepository,
    private val organizationRepository: OrganizationRepository
) {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasourceProps(registry: DynamicPropertyRegistry) {
            SharedContainers.registerDatabase(registry, "concurrent_charge")
        }
    }

    @Test
    fun `동시에 충전해도 증가분이 유실되지 않는다`() {
        val organization = organizationRepository.save(Organization("acme", 10_000L))

        runConcurrently(10) {
            organizationService.charge(organization.persistedId, 300L)
        }

        assertThat(ledgerRepository.findByOrganizationIdOrderByIdDesc(organization.persistedId))
            .filteredOn { it.type == LedgerType.CHARGE }
            .hasSize(10)

        val found = organizationRepository.findById(organization.persistedId).orElseThrow()
        assertThat(found.balance).isEqualTo(10_000L + 3_000L)
    }
}
