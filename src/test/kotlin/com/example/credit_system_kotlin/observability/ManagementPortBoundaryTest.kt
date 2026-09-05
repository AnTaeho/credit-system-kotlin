package com.example.credit_system_kotlin.observability

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalManagementPort
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpStatus
import org.springframework.test.context.ActiveProfiles

/**
 * 노출 경계가 실제로 존재하는지 확인한다.
 *
 * `management.server.port` 를 애플리케이션 포트와 다르게 주면 액추에이터는 별도 포트로
 * 옮겨간다. 이 테스트는 그 상태에서 **애플리케이션 포트로는 `/actuator/prometheus` 에
 * 닿을 수 없다**는 것을 404 로 못 박는다. 설정이 되돌려지면 이 테스트가 깨진다.
 *
 * 배포(docker-compose)에서는 관리 포트를 호스트로 publish 하지 않아서 컨테이너 네트워크
 * 밖에서는 아예 닿을 수 없다. 여기서는 그 배포 결정의 앞단, 즉 "포트가 실제로 갈라진다"를 본다.
 */
@ActiveProfiles("test")
@AutoConfigureTestRestTemplate
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["management.server.port=0"]
)
class ManagementPortBoundaryTest @Autowired constructor(
    private val restTemplate: TestRestTemplate
) {

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
    fun `애플리케이션 포트에서는 prometheus 엔드포인트에 닿을 수 없다`() {
        val response = restTemplate.getForEntity(
            "http://localhost:$serverPort/actuator/prometheus",
            String::class.java
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `관리 포트에서는 prometheus 엔드포인트가 도메인 지표를 노출한다`() {
        val response = restTemplate.getForEntity(
            "http://localhost:$managementPort/actuator/prometheus",
            String::class.java
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).contains("credit_defense_total")
        assertThat(response.body).contains("credit_job_oldest_pending_age_seconds")
    }
}
