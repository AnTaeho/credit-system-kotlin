package com.example.credit_system_kotlin.job.service

import com.example.credit_system_kotlin.global.config.appProperties
import com.example.credit_system_kotlin.job.repository.JobRepository
import com.example.credit_system_kotlin.organization.domain.Organization
import com.example.credit_system_kotlin.organization.repository.OrganizationRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.test.context.ActiveProfiles

@ActiveProfiles("test")
@DataJpaTest
class HoldServiceTest @Autowired constructor(
    private val organizationRepository: OrganizationRepository,
    private val jobRepository: JobRepository
) {

    private val holdService = HoldService(
        organizationRepository, jobRepository,
        appProperties()
    )

    /**
     * 테스트 트랜잭션은 인스턴스 생성이 아니라 @BeforeEach 직전에 열린다.
     * 프로퍼티 초기화 자리에서 save하면 트랜잭션 밖에서 커밋되어 롤백되지 않으므로
     * 엔티티 준비는 반드시 @BeforeEach 안에서 한다.
     */
    private lateinit var organization: Organization

    @BeforeEach
    fun setUp() {
        organization = organizationRepository.save(Organization("acme", 1000L))
    }

    @Test
    fun `정상 요청은 잔액을 차감하고 job을 생성한다`() {
        holdService.requestGeneration(organization.persistedId, "a cat")

        val found = organizationRepository.findById(organization.persistedId).orElseThrow()
        assertThat(found.balance).isEqualTo(900L)
    }

    @Test
    fun `prompt의 최대 길이는 허용한다`() {
        val result = holdService.requestGeneration(organization.persistedId, "p".repeat(1000))

        assertThat(jobRepository.findById(result.jobId).orElseThrow().prompt).hasSize(1000)
    }
}
