package com.example.credit_system_kotlin.organization

import com.example.credit_system_kotlin.global.ErrorResponse
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
            HttpEntity(ChargeRequest("idem-1", 300L), headers), ChargeResponse::class.java
        )

        assertThat(after.body?.balance).isEqualTo(800L)
        assertThat(after.body?.duplicate).isFalse()
    }

    @Test
    fun `같은 idemKey로 두 번 충전하면 두 번째 응답은 중복이다`() {
        val headers = HttpHeaders()
        headers.add("X-Organization-Id", organization.persistedId.toString())
        headers.contentType = MediaType.APPLICATION_JSON

        val first = restTemplate.exchange(
            url("/api/organizations/me/charge"), HttpMethod.POST,
            HttpEntity(ChargeRequest("idem-dup", 300L), headers), ChargeResponse::class.java
        )
        val second = restTemplate.exchange(
            url("/api/organizations/me/charge"), HttpMethod.POST,
            HttpEntity(ChargeRequest("idem-dup", 300L), headers), ChargeResponse::class.java
        )

        assertThat(first.body?.duplicate).isFalse()
        assertThat(first.body?.balance).isEqualTo(800L)
        assertThat(second.body?.duplicate).isTrue()
        assertThat(second.body?.balance).isEqualTo(800L)
    }

    @Test
    fun `존재하지 않는 조직이면 404다`() {
        val headers = HttpHeaders()
        headers.add("X-Organization-Id", (organization.persistedId + 999_999L).toString())

        val response = restTemplate.exchange(
            url("/api/organizations/me/balance"), HttpMethod.GET,
            HttpEntity<Void>(headers), String::class.java
        )

        assertThat(response.statusCode.value()).isEqualTo(404)
    }

    /**
     * Java 원본은 ChargeServiceTest 에서 idemKey에 null을 넘겨 이 경계를 확인했다.
     * Kotlin은 idemKey를 non-null로 닫아(report.md A-4) 그 호출이 컴파일되지 않으므로
     * 남은 실제 경로 — 필드가 아예 없는 JSON — 를 여기서 확인한다.
     * 코드(INVALID_REQUEST)와 상태(400)는 Java와 같고 메시지 문구만 다르다.
     */
    @Test
    fun `idemKey 필드가 없는 본문은 400으로 거부된다`() {
        val headers = HttpHeaders()
        headers.add("X-Organization-Id", organization.persistedId.toString())
        headers.contentType = MediaType.APPLICATION_JSON

        val response = restTemplate.exchange(
            url("/api/organizations/me/charge"), HttpMethod.POST,
            HttpEntity("""{"amount":300}""", headers), ErrorResponse::class.java
        )

        assertThat(response.statusCode.value()).isEqualTo(400)
        assertThat(response.body?.code).isEqualTo("INVALID_REQUEST")
        assertThat(organizationRepository.findById(organization.persistedId).orElseThrow().balance)
            .isEqualTo(500L)
    }

    private fun url(path: String) = "http://localhost:$port$path"
}
