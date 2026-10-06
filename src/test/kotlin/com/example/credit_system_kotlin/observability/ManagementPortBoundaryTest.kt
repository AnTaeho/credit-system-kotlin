package com.example.credit_system_kotlin.observability

import com.example.credit_system_kotlin.auth.token.AccessTokenService
import com.example.credit_system_kotlin.support.TestTokens
import com.example.credit_system_kotlin.user.domain.UserRole
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.http.client.HttpRedirects
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalManagementPort
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.test.context.ActiveProfiles

/**
 * 관리 포트를 따로 주면 애플리케이션 포트로는 `/actuator/prometheus` 에 닿을 수 없는지 확인한다.
 * 관리 포트에도 보안 필터 체인이 걸려서, health·prometheus 가 인증 없이 열리는지도 여기서 본다.
 */
@ActiveProfiles("test")
@AutoConfigureTestRestTemplate
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    // Redis 상태는 health 판정에서 뺀다. 이 테스트가 보는 것은 "인증 없이 닿는가"이지 "시스템이 건강한가"가
    // 아니다. 로컬에 Redis 가 떠 있으면 200, CI 처럼 없으면 503 이 되어 환경에 따라 결과가 갈렸다.
    properties = ["management.server.port=0", "management.health.redis.enabled=false"]
)
class ManagementPortBoundaryTest @Autowired constructor(
    restTemplate: TestRestTemplate,
    accessTokenService: AccessTokenService
) {

    private val tokens = TestTokens(accessTokenService)

    // 미인증 요청은 로그인 화면으로 리다이렉트된다. 따라가면 로그인 화면의 200 을 보게 되므로 멈춘다.
    private val restTemplate = restTemplate.withRedirects(HttpRedirects.DONT_FOLLOW)

    @LocalServerPort
    private var serverPort: Int = 0

    @LocalManagementPort
    private var managementPort: Int = 0

    @Test
    fun `애플리케이션 포트에서는 로그인해도 prometheus 엔드포인트가 없다`() {
        // 액추에이터는 사용자 행을 읽지 않는다. 운영자 역할이 든 토큰이면 충분하다.
        val headers = HttpHeaders()
        headers.add(HttpHeaders.AUTHORIZATION, tokens.bearer(userId = 1L, role = UserRole.ADMIN))

        val response = restTemplate.exchange(
            "http://localhost:$serverPort/actuator/prometheus", HttpMethod.GET,
            HttpEntity<Void>(headers), String::class.java
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `애플리케이션 포트에서 미인증으로 prometheus 를 찌르면 지표 대신 인증을 요구한다`() {
        val response = restTemplate.getForEntity(
            "http://localhost:$serverPort/actuator/prometheus",
            String::class.java
        )

        // /api 밖의 미인증 요청이라 로그인 화면으로 보낸다. 지표는 없다.
        assertThat(response.statusCode).isEqualTo(HttpStatus.FOUND)
        assertThat(response.headers.location?.path).isEqualTo("/login")
        assertThat(response.body.orEmpty()).doesNotContain("credit_defense_total")
    }

    @Test
    fun `관리 포트에서는 인증 없이 prometheus 엔드포인트가 도메인 지표를 노출한다`() {
        val response = restTemplate.getForEntity(
            "http://localhost:$managementPort/actuator/prometheus",
            String::class.java
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).contains("credit_defense_total")
        assertThat(response.body).contains("credit_job_oldest_pending_age_seconds")
        assertThat(response.body).contains("credit_ledger_reconciliation_mismatch")
    }

    @Test
    fun `관리 포트에서는 인증 없이 health 가 200이다`() {
        val response = restTemplate.getForEntity(
            "http://localhost:$managementPort/actuator/health",
            String::class.java
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
    }
}
