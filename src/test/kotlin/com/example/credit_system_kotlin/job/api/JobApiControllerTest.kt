package com.example.credit_system_kotlin.job.api

import com.example.credit_system_kotlin.organization.Organization
import com.example.credit_system_kotlin.organization.OrganizationRepository
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
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles

@ActiveProfiles("test")
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class JobApiControllerTest @Autowired constructor(
    private val restTemplate: TestRestTemplate,
    private val organizationRepository: OrganizationRepository
) {

    @field:LocalServerPort
    private var port: Int = 0

    private lateinit var organization: Organization

    @BeforeEach
    fun setUp() {
        organization = organizationRepository.save(Organization("acme", 1000L))
    }

    @AfterEach
    fun tearDown() {
        organizationRepository.deleteAll()
    }

    @Test
    fun `조직 헤더 없이 호출하면 400이다`() {
        val response = restTemplate.getForEntity(url("/api/jobs"), String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `생성 요청과 목록 조회가 정상 동작한다`() {
        val headers = HttpHeaders()
        headers.add("X-Organization-Id", organization.persistedId.toString())
        headers.contentType = MediaType.APPLICATION_JSON

        val createResponse = restTemplate.exchange(
            url("/api/jobs"), HttpMethod.POST,
            HttpEntity(JobCreateRequest("idem-1", "a cat"), headers),
            HoldResult::class.java
        )

        assertThat(createResponse.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(createResponse.body?.duplicate).isFalse()

        val listResponse = restTemplate.exchange(
            url("/api/jobs"), HttpMethod.GET, HttpEntity<Void>(headers),
            Array<JobResponse>::class.java
        )

        val jobs = listResponse.body?.toList().orEmpty()
        assertThat(jobs).hasSize(1)
        assertThat(jobs[0].status).isEqualTo("HOLDING")
    }

    private fun url(path: String) = "http://localhost:$port$path"
}
