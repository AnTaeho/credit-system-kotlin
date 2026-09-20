package com.example.credit_system_kotlin.global.logging

import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

class RequestIdFilterTest {

    private val filter = RequestIdFilter()

    @AfterEach
    fun clearMdc() {
        MDC.clear()
    }

    @Test
    fun `헤더가 없으면 만들어서 응답 헤더에 싣는다`() {
        val response = MockHttpServletResponse()

        filter.doFilter(get(), response, MockFilterChain())

        val issued = response.getHeader(RequestIdFilter.HEADER)
        assertThat(issued).isNotNull()
        assertThat(RequestIdFilter.isAcceptable(issued!!)).isTrue()
    }

    @Test
    fun `들어온 값이 정상이면 그대로 쓴다`() {
        val response = MockHttpServletResponse()

        filter.doFilter(get("edge-7f3a.1:b"), response, MockFilterChain())

        assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo("edge-7f3a.1:b")
    }

    @Test
    fun `이상한 값은 버리고 새로 만든다`() {
        // 줄바꿈이 끼면 가짜 로그 한 줄을 심을 수 있고, 긴 값은 저장소를 밀어낸다. 공백·한글도 받지 않는다.
        val rejected = listOf(
            "abc\ndef",
            "abc\r\nWARN 이것은 가짜 로그 줄이다",
            "a".repeat(RequestIdFilter.MAX_LENGTH + 1),
            "has space",
            "",
            "한글"
        )

        rejected.forEach { bad ->
            val response = MockHttpServletResponse()

            filter.doFilter(get(bad), response, MockFilterChain())

            val issued = response.getHeader(RequestIdFilter.HEADER)
            assertThat(issued).`as`("거부 대상: %s", bad).isNotEqualTo(bad)
            assertThat(RequestIdFilter.isAcceptable(issued!!)).isTrue()
        }
    }

    @Test
    fun `체인이 도는 동안에만 MDC 에 있고 끝나면 지운다`() {
        var duringChain: String? = null
        val chain = FilterChain { _: ServletRequest, _: ServletResponse ->
            duringChain = MDC.get(LogContext.REQUEST_ID)
        }

        filter.doFilter(get("during-chain"), MockHttpServletResponse(), chain)

        assertThat(duringChain).isEqualTo("during-chain")
        assertThat(MDC.get(LogContext.REQUEST_ID)).isNull()
    }

    @Test
    fun `체인이 예외를 던져도 MDC 를 지운다`() {
        val chain = FilterChain { _: ServletRequest, _: ServletResponse ->
            throw IllegalStateException("체인 폭발")
        }

        assertThatThrownBy { filter.doFilter(get(), MockHttpServletResponse(), chain) }
            .isInstanceOf(IllegalStateException::class.java)
        assertThat(MDC.get(LogContext.REQUEST_ID)).isNull()
    }

    private fun get(incomingRequestId: String? = null): MockHttpServletRequest {
        val request = MockHttpServletRequest("GET", "/api/jobs")
        if (incomingRequestId != null) {
            request.addHeader(RequestIdFilter.HEADER, incomingRequestId)
        }
        return request
    }
}
