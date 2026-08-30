package com.example.credit_system_kotlin.organization.service

import com.example.credit_system_kotlin.global.exception.InvalidRequestException
import com.example.credit_system_kotlin.global.exception.OrganizationNotFoundException
import com.example.credit_system_kotlin.ledger.domain.LedgerType
import com.example.credit_system_kotlin.ledger.repository.LedgerRepository
import com.example.credit_system_kotlin.organization.domain.Organization
import com.example.credit_system_kotlin.organization.repository.OrganizationRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.test.context.ActiveProfiles

@ActiveProfiles("test")
@DataJpaTest
class OrganizationServiceTest @Autowired constructor(
    private val organizationRepository: OrganizationRepository,
    private val ledgerRepository: LedgerRepository
) {

    private val organizationService =
        OrganizationService(organizationRepository, OrganizationFinder(organizationRepository), ledgerRepository)

    @Test
    fun `충전하면 잔액이 증가하고 ledger에 CHARGE가 남는다`() {
        val organization = organizationRepository.save(Organization("acme", 500L))

        val response = organizationService.charge(organization.persistedId, 300L)

        assertThat(response.balance).isEqualTo(800L)
        val found = organizationRepository.findById(organization.persistedId).orElseThrow()
        assertThat(found.balance).isEqualTo(800L)
        assertThat(ledgerRepository.findByOrganizationIdOrderByIdDesc(organization.persistedId))
            .anyMatch { it.type == LedgerType.CHARGE }
    }

    @Test
    fun `충전 금액이 0 이하면 거부한다`() {
        val organization = organizationRepository.save(Organization("acme", 500L))

        assertThatThrownBy { organizationService.charge(organization.persistedId, 0L) }
            .isInstanceOf(InvalidRequestException::class.java)
            .hasMessage("amount는 0보다 커야 합니다.")

        assertThat(organizationRepository.findById(organization.persistedId).orElseThrow().balance)
            .isEqualTo(500L)
        assertThat(ledgerRepository.findByOrganizationIdOrderByIdDesc(organization.persistedId)).isEmpty()
    }

    @Test
    fun `충전 금액이 상한을 초과하면 거부한다`() {
        val organization = organizationRepository.save(Organization("acme", 500L))

        assertThatThrownBy { organizationService.charge(organization.persistedId, 1_000_001L) }
            .isInstanceOf(InvalidRequestException::class.java)
            .hasMessage("amount는 1,000,000을 초과할 수 없습니다.")

        assertThat(organizationRepository.findById(organization.persistedId).orElseThrow().balance)
            .isEqualTo(500L)
        assertThat(ledgerRepository.findByOrganizationIdOrderByIdDesc(organization.persistedId)).isEmpty()
    }

    @Test
    fun `존재하지 않는 조직을 충전하면 예외가 발생한다`() {
        val organization = organizationRepository.save(Organization("acme", 500L))
        val missingOrganizationId = organization.persistedId + 999_999L

        assertThatThrownBy { organizationService.charge(missingOrganizationId, 300L) }
            .isInstanceOf(OrganizationNotFoundException::class.java)
            .hasMessage("존재하지 않는 organization: $missingOrganizationId")
    }
}
