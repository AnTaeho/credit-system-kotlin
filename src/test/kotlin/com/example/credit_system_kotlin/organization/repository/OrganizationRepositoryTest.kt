package com.example.credit_system_kotlin.organization.repository

import com.example.credit_system_kotlin.organization.domain.Organization
import com.example.credit_system_kotlin.support.persistedId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.test.context.ActiveProfiles
import java.time.Instant

@ActiveProfiles("test")
@DataJpaTest
class OrganizationRepositoryTest @Autowired constructor(
    private val organizationRepository: OrganizationRepository
) {

    @Test
    fun `잔액이 충분하면 차감된다`() {
        val org = organizationRepository.save(Organization("acme", 1000L))

        val updated = organizationRepository.deductBalance(org.persistedId, 300L, Instant.now())
        organizationRepository.flush()

        val found = organizationRepository.findById(org.persistedId).orElseThrow()
        assertThat(updated).isEqualTo(1)
        assertThat(found.balance).isEqualTo(700L)
    }

    @Test
    fun `잔액이 부족하면 0행이 반환되고 잔액이 변하지 않는다`() {
        val org = organizationRepository.save(Organization("acme", 100L))

        val updated = organizationRepository.deductBalance(org.persistedId, 300L, Instant.now())

        val found = organizationRepository.findById(org.persistedId).orElseThrow()
        assertThat(updated).isZero()
        assertThat(found.balance).isEqualTo(100L)
    }

    @Test
    fun `환불은 잔액을 되돌린다`() {
        val org = organizationRepository.save(Organization("acme", 700L))

        val updated = organizationRepository.addBalance(org.persistedId, 300L, Instant.now())

        val found = organizationRepository.findById(org.persistedId).orElseThrow()
        assertThat(updated).isEqualTo(1)
        assertThat(found.balance).isEqualTo(1000L)
    }
}
