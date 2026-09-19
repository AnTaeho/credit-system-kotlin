package com.example.credit_system_kotlin.auth.login

import com.example.credit_system_kotlin.auth.config.AuthProperties
import com.example.credit_system_kotlin.auth.config.maskEmail
import com.example.credit_system_kotlin.auth.config.normalizeEmail
import com.example.credit_system_kotlin.auth.web.SecurityErrorWriter
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

private val log = LoggerFactory.getLogger(DevLoginFilter::class.java)

/**
 * 로컬 전용 헤더 로그인. `X-Dev-User: <email>` 하나로 그 사람이 된다.
 *
 * 구글을 거치지 않을 뿐 허용 목록 검사와 권한 부여는 구글 로그인과 같다. 인증은 **이 요청 하나에만**
 * 걸리고 세션에 저장되지 않는다. 쿠키로 인증되지 않으므로 CSRF 검사 대상이 아니다(SecurityConfig 참고).
 *
 * 빈으로 등록하지 않는다. `@Component` 필터는 서블릿 컨테이너에도 자동 등록돼 보안 체인 밖에서 한 번 더 돈다.
 * `app.auth.dev-login.enabled=true` 일 때만 SecurityConfig 가 체인에 끼운다.
 */
class DevLoginFilter(
    private val authProperties: AuthProperties,
    private val provisioner: UserAccountProvisioner,
    private val errorWriter: SecurityErrorWriter
) : OncePerRequestFilter() {

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val header = request.getHeader(HEADER)
        if (header.isNullOrBlank()) {
            chain.doFilter(request, response)
            return
        }

        val email = normalizeEmail(header)
        if (!authProperties.isAllowed(email)) {
            log.warn("개발 로그인 거부: 허용 목록 밖, email={}", maskEmail(email))
            errorWriter.write(response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHENTICATED", "로그인이 허용되지 않은 계정입니다.")
            return
        }

        val userId = provisioner.provisionDevUser(email)
        val authentication = UsernamePasswordAuthenticationToken.authenticated(
            DevLoginUser(userId, email),
            null,
            authoritiesFor(authProperties.isAdmin(email))
        )
        val context = SecurityContextHolder.createEmptyContext()
        context.authentication = authentication
        SecurityContextHolder.setContext(context)
        chain.doFilter(request, response)
    }

    companion object {
        const val HEADER = "X-Dev-User"
    }
}
