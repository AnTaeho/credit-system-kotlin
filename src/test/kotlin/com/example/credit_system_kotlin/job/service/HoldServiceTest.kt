package com.example.credit_system_kotlin.job.service

import com.example.credit_system_kotlin.global.config.appProperties
import com.example.credit_system_kotlin.global.exception.InsufficientBalanceException
import com.example.credit_system_kotlin.global.exception.InvalidRequestException
import com.example.credit_system_kotlin.global.exception.OrganizationNotFoundException
import com.example.credit_system_kotlin.job.repository.JobRepository
import com.example.credit_system_kotlin.ledger.repository.LedgerRepository
import com.example.credit_system_kotlin.organization.domain.Organization
import com.example.credit_system_kotlin.organization.repository.OrganizationRepository
import com.example.credit_system_kotlin.organization.service.OrganizationFinder
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.test.context.ActiveProfiles

@ActiveProfiles("test")
@DataJpaTest
class HoldServiceTest @Autowired constructor(
    private val organizationRepository: OrganizationRepository,
    private val jobRepository: JobRepository,
    private val ledgerRepository: LedgerRepository
) {

    private val holdService = HoldService(
        organizationRepository, OrganizationFinder(organizationRepository), jobRepository, ledgerRepository,
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
    fun `정상 요청은 잔액을 차감하고 job과 ledger를 생성한다`() {
        holdService.requestGeneration(organization.persistedId, "a cat")

        val found = organizationRepository.findById(organization.persistedId).orElseThrow()
        assertThat(found.balance).isEqualTo(900L)
        assertThat(ledgerRepository.findByOrganizationIdOrderByIdDesc(organization.persistedId)).hasSize(1)
    }

    @Test
    fun `잔액이 부족하면 예외가 발생하고 job이 생성되지 않는다`() {
        val poor = organizationRepository.save(Organization("poor", 50L))

        assertThatThrownBy { holdService.requestGeneration(poor.persistedId, "a cat") }
            .isInstanceOf(InsufficientBalanceException::class.java)

        assertThat(jobRepository.findByOrganizationIdOrderByIdDesc(poor.persistedId)).isEmpty()
        assertThat(ledgerRepository.findByOrganizationIdOrderByIdDesc(poor.persistedId)).isEmpty()
    }

    @Test
    fun `필수값이 없거나 길이 제한을 넘으면 요청을 거부한다`() {
        assertThatThrownBy { holdService.requestGeneration(organization.persistedId, " ") }
            .isInstanceOf(InvalidRequestException::class.java)
            .hasMessage("prompt는 필수입니다.")
        assertThatThrownBy {
            holdService.requestGeneration(organization.persistedId, "a".repeat(1001))
        }
            .isInstanceOf(InvalidRequestException::class.java)
            .hasMessage("prompt는 1000자를 초과할 수 없습니다.")

        assertThat(jobRepository.findByOrganizationIdOrderByIdDesc(organization.persistedId)).isEmpty()
    }

    @Test
    fun `prompt의 최대 길이는 허용한다`() {
        val result = holdService.requestGeneration(organization.persistedId, "p".repeat(1000))

        assertThat(jobRepository.findById(result.jobId).orElseThrow().prompt).hasSize(1000)
    }

    @Test
    fun `존재하지 않는 조직의 생성 요청이면 예외가 발생한다`() {
        val missingOrganizationId = organization.persistedId + 999_999L

        assertThatThrownBy {
            holdService.requestGeneration(missingOrganizationId, "a cat")
        }
            .isInstanceOf(OrganizationNotFoundException::class.java)
            .hasMessage("존재하지 않는 organization: $missingOrganizationId")

        assertThat(jobRepository.count()).isZero()
        assertThat(ledgerRepository.count()).isZero()
    }
}
