package com.example.credit_system_kotlin.observability

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpStatus
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get

/**
 * 관리 포트를 따로 주지 않은 기동(관리 포트 = 애플리케이션 포트)에서는 액추에이터가 공개 포트에
 * 섞인다. 그때는 누구에게도 열지 않는다. 운영자로 로그인해도 막힌다.
 *
 * 지표 내용 자체(원장 대사 지표 노출)는 관리 포트를 분리한 [ManagementPortBoundaryTest] 가 확인한다.
 */
@ActiveProfiles("test")
@AutoConfigureMockMvc
@SpringBootTest
class PrometheusEndpointTest @Autowired constructor(
    private val mockMvc: MockMvc
) {

    @Test
    fun `같은 포트에서는 운영자라도 prometheus 엔드포인트에 닿을 수 없다`() {
        val result = mockMvc.perform(get("/actuator/prometheus").header("X-Dev-User", "admin@test.local")).andReturn()

        assertThat(result.response.status).isEqualTo(HttpStatus.FORBIDDEN.value())
        assertThat(result.response.contentAsString).doesNotContain("credit_ledger_reconciliation_mismatch")
    }

    @Test
    fun `같은 포트에서는 미인증 health 도 열리지 않는다`() {
        val result = mockMvc.perform(get("/actuator/health")).andReturn()

        assertThat(result.response.status).isNotEqualTo(HttpStatus.OK.value())
    }
}
