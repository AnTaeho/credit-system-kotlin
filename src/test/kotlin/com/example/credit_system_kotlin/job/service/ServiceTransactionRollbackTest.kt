package com.example.credit_system_kotlin.job.service

import com.example.credit_system_kotlin.global.exception.InsufficientBalanceException
import com.example.credit_system_kotlin.job.repository.JobRepository
import com.example.credit_system_kotlin.organization.domain.Organization
import com.example.credit_system_kotlin.organization.repository.OrganizationRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles

@ActiveProfiles("test")
@SpringBootTest
class ServiceTransactionRollbackTest @Autowired constructor(
    private val holdService: HoldService,
    private val jobRepository: JobRepository,
    private val organizationRepository: OrganizationRepository
) {

    @AfterEach
    fun tearDown() {
        jobRepository.deleteAll()
        organizationRepository.deleteAll()
    }

    @Test
    fun `잔액 부족으로 hold가 실패하면 job도 롤백된다`() {
        val organization = organizationRepository.save(Organization("poor", 50L))

        assertThatThrownBy {
            holdService.requestGeneration(organization.persistedId, "cat")
        }
            .isInstanceOf(InsufficientBalanceException::class.java)

        assertThat(jobRepository.findByOrganizationIdOrderByIdDesc(organization.persistedId)).isEmpty()
        assertThat(organizationRepository.findById(organization.persistedId).orElseThrow().balance)
            .isEqualTo(50L)
    }
}
