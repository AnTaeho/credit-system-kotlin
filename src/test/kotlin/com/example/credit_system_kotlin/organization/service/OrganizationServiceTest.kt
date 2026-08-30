package com.example.credit_system_kotlin.organization.service

import com.example.credit_system_kotlin.organization.domain.Organization
import com.example.credit_system_kotlin.organization.repository.OrganizationRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.test.context.ActiveProfiles

@ActiveProfiles("test")
@DataJpaTest
class OrganizationServiceTest @Autowired constructor(
    private val organizationRepository: OrganizationRepository
) {

    private val organizationService = OrganizationService(organizationRepository)

    @Test
    fun `충전하면 잔액이 증가한다`() {
        val organization = organizationRepository.save(Organization("acme", 500L))

        val response = organizationService.charge(organization.persistedId, 300L)

        assertThat(response.balance).isEqualTo(800L)
        val found = organizationRepository.findById(organization.persistedId).orElseThrow()
        assertThat(found.balance).isEqualTo(800L)
    }
}
