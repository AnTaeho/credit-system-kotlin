package com.example.credit_system_kotlin.job.concurrency

import com.example.credit_system_kotlin.ledger.domain.LedgerType
import com.example.credit_system_kotlin.ledger.repository.LedgerRepository
import com.example.credit_system_kotlin.organization.domain.Organization
import com.example.credit_system_kotlin.organization.repository.OrganizationRepository
import com.example.credit_system_kotlin.organization.service.ChargeService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

@ActiveProfiles("test")
@SpringBootTest
class ConcurrentChargeTest @Autowired constructor(
    private val chargeService: ChargeService,
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
    fun `동일 idemKey로 동시 충전해도 잔액은 한 번만 오른다`() {
        val organization = organizationRepository.save(Organization("acme", 10_000L))
        val idemKey = "shared-charge-key"

        runConcurrently(10) {
            try {
                chargeService.charge(organization.persistedId, idemKey, 300L)
            } catch (e: DataIntegrityViolationException) {
                // 유니크 제약에서 밀린 쪽. 잔액이 오르지 않는 것이 정상이므로 무시한다.
            }
        }

        assertThat(ledgerRepository.findByOrganizationIdOrderByIdDesc(organization.persistedId))
            .filteredOn { it.type == LedgerType.CHARGE }
            .hasSize(1)

        val found = organizationRepository.findById(organization.persistedId).orElseThrow()
        assertThat(found.balance).isEqualTo(10_000L + 300L)
    }
}
