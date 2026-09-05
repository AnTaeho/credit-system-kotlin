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
 * `/actuator/prometheus` 가 원장 대사 지표를 실제로 노출하는지 확인하는 통합 테스트다.
 *
 * Micrometer 의 점 표기법(`credit.ledger.reconciliation.mismatch`)은 Prometheus
 * 스크레이프 포맷에서 언더스코어(`credit_ledger_reconciliation_mismatch`)로 바뀌므로
 * 언더스코어로 검색해야 한다.
 */
@ActiveProfiles("test")
@AutoConfigureMockMvc
@SpringBootTest
class PrometheusEndpointTest @Autowired constructor(
    private val mockMvc: MockMvc
) {

    @Test
    fun `prometheus 엔드포인트가 원장 대사 지표를 노출한다`() {
        val result = mockMvc.perform(get("/actuator/prometheus")).andReturn()

        assertThat(result.response.status).isEqualTo(HttpStatus.OK.value())
        assertThat(result.response.contentAsString).contains("credit_ledger_reconciliation_mismatch")
        assertThat(result.response.contentAsString).contains("credit_job_oldest_pending_age_seconds")
    }
}
