package com.example.credit_system_kotlin.global.logging

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

/**
 * HTTP 요청 한 번에 식별자를 붙여 [LogContext.REQUEST_ID] 로 MDC 에 심는다. 응답 헤더로도 돌려준다.
 *
 * **왜 필터 체인의 맨 앞인가.** 스프링 시큐리티 필터 체인은 `SecurityProperties.DEFAULT_FILTER_ORDER`(-100)에
 * 등록된다. 이 필터가 그보다 뒤에 있으면 인증 실패 401·CSRF 실패 403 응답에는 식별자가 없다. 그런데
 * 추적이 가장 필요한 응답이 바로 그것들이다("누가 왜 튕겼나"). 그래서 [Ordered.HIGHEST_PRECEDENCE] 로
 * 시큐리티보다 앞에 두고, 응답 헤더도 `chain.doFilter` **전에** 박는다 — 시큐리티가 응답을 먼저 커밋해도
 * 헤더는 이미 붙어 있다.
 *
 * **들어온 값을 그대로 믿지 않는 이유.** `X-Request-Id` 는 클라이언트가 보내는 값이라 로그에 그대로 실으면
 * 남이 우리 로그에 쓰는 것과 같다. 줄바꿈 한 글자면 로그 한 줄을 위조할 수 있고, 긴 값이면 저장소를 밀어낸다.
 * [isAcceptable] 을 통과한 값만 쓰고, 아니면 조용히 새로 만든다(거부해서 요청을 죽일 일은 아니다).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestIdFilter : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val requestId = resolveRequestId(request.getHeader(HEADER))
        response.setHeader(HEADER, requestId)
        MDC.put(LogContext.REQUEST_ID, requestId)
        try {
            filterChain.doFilter(request, response)
        } finally {
            // 서블릿 컨테이너도 스레드를 재사용한다. 여기서 안 지우면 다음 요청의 로그에 남의 식별자가 붙는다.
            MDC.remove(LogContext.REQUEST_ID)
        }
    }

    private fun resolveRequestId(incoming: String?): String =
        if (incoming != null && isAcceptable(incoming)) incoming else newRequestId()

    private fun newRequestId(): String = UUID.randomUUID().toString().replace("-", "")

    companion object {
        const val HEADER = "X-Request-Id"

        /** 64자 이하의 영숫자와 `_ - . :` 만. 공백·제어문자·줄바꿈이 낄 자리를 아예 없앤다. */
        const val MAX_LENGTH = 64
        private val ACCEPTABLE = Regex("^[A-Za-z0-9_.:-]{1,$MAX_LENGTH}$")

        fun isAcceptable(value: String): Boolean = ACCEPTABLE.matches(value)
    }
}
