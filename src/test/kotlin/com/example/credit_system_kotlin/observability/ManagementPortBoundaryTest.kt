package com.example.credit_system_kotlin.observability

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
 * 노출 경계가 실제로 존재하는지 확인한다.
 *
 * `management.server.port` 를 애플리케이션 포트와 다르게 주면 액추에이터는 별도 포트로
 * 옮겨간다. 이 테스트는 그 상태에서 **애플리케이션 포트로는 `/actuator/prometheus` 에
 * 닿을 수 없다**는 것을 못 박는다. 설정이 되돌려지면 이 테스트가 깨진다.
 *
 * 보안 필터 체인은 관리 포트의 자식 컨텍스트에도 걸린다. 그래서 관리 포트의 health·prometheus 가
 * 인증 없이 열려 있는 것은 SecurityConfig 의 규칙 덕분이고, 그것도 여기서 확인한다.
 * Prometheus 스크레이프와 compose 헬스체크는 로그인하지 않는다.
 *
 * 배포(docker-compose)에서는 관리 포트를 호스트로 publish 하지 않아서 컨테이너 네트워크
 * 밖에서는 아예 닿을 수 없다. 여기서는 그 배포 결정의 앞단, 즉 "포트가 실제로 갈라진다"를 본다.
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
    restTemplate: TestRestTemplate
) {

    // 미인증 요청은 구글 로그인으로 리다이렉트된다. 따라가면 테스트가 외부로 나가므로 멈춘다.
    private val restTemplate = restTemplate.withRedirects(HttpRedirects.DONT_FOLLOW)

    @LocalServerPort
    private var serverPort: Int = 0

    @LocalManagementPort
    private var managementPort: Int = 0

    @Test
    fun `애플리케이션 포트와 관리 포트가 서로 다르다`() {
        assertThat(managementPort).isNotZero()
        assertThat(managementPort).isNotEqualTo(serverPort)
    }

    @Test
    fun `애플리케이션 포트에서는 로그인해도 prometheus 엔드포인트가 없다`() {
        val headers = HttpHeaders()
        headers.add("X-Dev-User", "admin@test.local")

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

        // 브라우저(Accept: text/html)면 구글 로그인으로 302, 그 밖의 클라이언트는 401 이다. 어느 쪽이든 지표는 없다.
        assertThat(response.statusCode).isIn(HttpStatus.FOUND, HttpStatus.UNAUTHORIZED)
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
