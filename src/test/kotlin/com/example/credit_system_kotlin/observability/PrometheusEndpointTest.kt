package com.example.credit_system_kotlin.observability

import com.example.credit_system_kotlin.auth.token.AccessTokenService
import com.example.credit_system_kotlin.support.TestTokens
import com.example.credit_system_kotlin.user.domain.UserRole
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get

/**
 * 관리 포트를 따로 주지 않으면 액추에이터가 공개 포트에 섞인다. 그때는 운영자로 로그인해도 막힌다.
 * 지표 내용은 [ManagementPortBoundaryTest] 가 확인한다.
 */
@ActiveProfiles("test")
@AutoConfigureMockMvc
@SpringBootTest
class PrometheusEndpointTest @Autowired constructor(
    private val mockMvc: MockMvc,
    accessTokenService: AccessTokenService
) {

    private val tokens = TestTokens(accessTokenService)

    @Test
    fun `같은 포트에서는 운영자라도 prometheus 엔드포인트에 닿을 수 없다`() {
        // 액추에이터는 사용자 행을 읽지 않는다. 운영자 역할이 든 토큰이면 충분하다.
        val admin = tokens.bearer(userId = 1L, role = UserRole.ADMIN)
        val result = mockMvc.perform(get("/actuator/prometheus").header(HttpHeaders.AUTHORIZATION, admin)).andReturn()

        assertThat(result.response.status).isEqualTo(HttpStatus.FORBIDDEN.value())
        assertThat(result.response.contentAsString).doesNotContain("credit_ledger_reconciliation_mismatch")
    }

    @Test
    fun `같은 포트에서는 미인증 health 도 열리지 않는다`() {
        val result = mockMvc.perform(get("/actuator/health")).andReturn()

        assertThat(result.response.status).isNotEqualTo(HttpStatus.OK.value())
    }
}
