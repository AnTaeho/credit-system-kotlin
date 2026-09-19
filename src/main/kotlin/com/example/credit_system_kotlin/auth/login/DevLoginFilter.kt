package com.example.credit_system_kotlin.auth.login

import com.example.credit_system_kotlin.auth.web.SecurityErrorWriter
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

/**
 * 로컬 전용 헤더 로그인. `X-Dev-User: <email>` 하나로 그 사람이 된다.
 *
 * 허용 목록 검사와 권한 부여는 [DevLoginAuthenticator] 가 한다(세션 개발 로그인과 같은 판정). 인증은
 * **이 요청 하나에만** 걸리고 세션에 저장되지 않는다. 쿠키로 인증되지 않으므로 CSRF 검사 대상이 아니다(SecurityConfig 참고).
 *
 * 빈으로 등록하지 않는다. `@Component` 필터는 서블릿 컨테이너에도 자동 등록돼 보안 체인 밖에서 한 번 더 돈다.
 * `app.auth.dev-login.enabled=true` 일 때만 SecurityConfig 가 체인에 끼운다.
 */
class DevLoginFilter(
    private val authenticator: DevLoginAuthenticator,
    private val errorWriter: SecurityErrorWriter
) : OncePerRequestFilter() {

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val header = request.getHeader(HEADER)
        if (header.isNullOrBlank()) {
            chain.doFilter(request, response)
            return
        }

        val authentication = authenticator.authenticate(header, channel = "header")
        if (authentication == null) {
            errorWriter.write(response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHENTICATED", "로그인이 허용되지 않은 계정입니다.")
            return
        }

        val context = SecurityContextHolder.createEmptyContext()
        context.authentication = authentication
        SecurityContextHolder.setContext(context)
        chain.doFilter(request, response)
    }

    companion object {
        const val HEADER = "X-Dev-User"
    }
}
