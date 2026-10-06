package com.example.credit_system_kotlin.auth.web

import com.example.credit_system_kotlin.auth.token.RefreshTokenService
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.Authentication
import org.springframework.security.web.authentication.logout.LogoutHandler

/**
 * 로그아웃 필터가 [TokenAuthenticationFilter] 보다 앞에서 돌아 인증 주체가 아직 없다. 그래서 쿠키를 직접 읽는다.
 * 이미 나간 액세스 토큰은 만료까지 유효하다. 빈으로 등록하지 않고 SecurityConfig 가 만들어 붙인다.
 */
class TokenLogoutHandler(
    private val refreshTokenService: RefreshTokenService,
    private val cookies: AuthCookies
) : LogoutHandler {

    override fun logout(request: HttpServletRequest, response: HttpServletResponse, authentication: Authentication?) {
        cookies.readRefresh(request)?.let(refreshTokenService::delete)
        cookies.clear(response)
    }
}
