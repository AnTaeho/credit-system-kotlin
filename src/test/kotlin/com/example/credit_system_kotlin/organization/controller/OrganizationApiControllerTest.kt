package com.example.credit_system_kotlin.organization.controller

import com.example.credit_system_kotlin.organization.domain.Organization
import com.example.credit_system_kotlin.organization.dto.BalanceResponse
import com.example.credit_system_kotlin.organization.dto.ChargeRequest
import com.example.credit_system_kotlin.organization.dto.ChargeResponse
import com.example.credit_system_kotlin.organization.repository.OrganizationRepository
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
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles

@ActiveProfiles("test")
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrganizationApiControllerTest @Autowired constructor(
    private val restTemplate: TestRestTemplate,
    private val organizationRepository: OrganizationRepository
) {

    @field:LocalServerPort
    private var port: Int = 0

    private lateinit var organization: Organization

    @BeforeEach
    fun setUp() {
        organization = organizationRepository.save(Organization("acme", 500L))
    }

    @AfterEach
    fun tearDown() {
        organizationRepository.deleteAll()
    }

    @Test
    fun `잔액 조회와 충전이 정상 동작한다`() {
        val headers = HttpHeaders()
        headers.add("X-Organization-Id", organization.persistedId.toString())

        val before = restTemplate.exchange(
            url("/api/organizations/me/balance"), HttpMethod.GET,
            HttpEntity<Void>(headers), BalanceResponse::class.java
        )
        assertThat(before.body?.balance).isEqualTo(500L)

        headers.contentType = MediaType.APPLICATION_JSON
        val after = restTemplate.exchange(
            url("/api/organizations/me/charge"), HttpMethod.POST,
            HttpEntity(ChargeRequest(300L), headers), ChargeResponse::class.java
        )

        assertThat(after.body?.balance).isEqualTo(800L)
    }

    private fun url(path: String) = "http://localhost:$port$path"
}
