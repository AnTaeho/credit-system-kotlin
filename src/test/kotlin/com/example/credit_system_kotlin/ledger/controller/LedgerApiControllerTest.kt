package com.example.credit_system_kotlin.ledger.controller

import com.example.credit_system_kotlin.ledger.domain.LedgerEntry
import com.example.credit_system_kotlin.ledger.domain.LedgerType
import com.example.credit_system_kotlin.ledger.dto.LedgerResponse
import com.example.credit_system_kotlin.ledger.repository.LedgerRepository
import com.example.credit_system_kotlin.organization.domain.Organization
import com.example.credit_system_kotlin.organization.repository.OrganizationRepository
import com.example.credit_system_kotlin.support.persistedId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.test.context.ActiveProfiles

@ActiveProfiles("test")
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LedgerApiControllerTest @Autowired constructor(
    private val restTemplate: TestRestTemplate,
    private val organizationRepository: OrganizationRepository,
    private val ledgerRepository: LedgerRepository
) {

    @field:LocalServerPort
    private var port: Int = 0

    private lateinit var organization: Organization

    @BeforeEach
    fun setUp() {
        organization = organizationRepository.save(Organization("acme", 1000L))
        ledgerRepository.save(LedgerEntry.of(organization.persistedId, 1L, LedgerType.HOLD, -100L))
        ledgerRepository.save(LedgerEntry.charge(organization.persistedId, "charge-key-1", 500L))
    }

    @AfterEach
    fun tearDown() {
        ledgerRepository.deleteAll()
        organizationRepository.deleteAll()
    }

    @Test
    fun `조직 헤더가 있으면 ledger 내역을 최신순으로 돌려준다`() {
        val headers = HttpHeaders()
        headers.add("X-Organization-Id", organization.persistedId.toString())

        val response = restTemplate.exchange(
            url("/api/ledger"), HttpMethod.GET, HttpEntity<Void>(headers),
            Array<LedgerResponse>::class.java
        )

        val entries = response.body?.toList().orEmpty()
        assertThat(entries).hasSize(2)
        assertThat(entries[0].type).isEqualTo("CHARGE")
        assertThat(entries[1].type).isEqualTo("HOLD")
    }

    @Test
    fun `조직 헤더 없이 호출하면 400이다`() {
        val response = restTemplate.getForEntity(url("/api/ledger"), String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    private fun url(path: String) = "http://localhost:$port$path"
}
