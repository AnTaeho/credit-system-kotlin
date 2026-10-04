package com.example.credit_system_kotlin.auth.web

import com.example.credit_system_kotlin.auth.token.RefreshTokenService
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.Authentication
import org.springframework.security.web.authentication.logout.LogoutHandler

/**
 * 로그아웃. 쿠키의 리프레시 토큰이 속한 사슬을 폐기하고 두 쿠키를 지운다.
 *
 * 로그아웃 필터는 [TokenAuthenticationFilter] 보다 앞에서 돈다. 그래서 인증 주체에 기대지 않고 쿠키를 직접 읽는다.
 * 액세스가 만료된 채로 로그아웃해도 리프레시가 회전되지 않고 그대로 폐기된다.
 * 이미 나간 액세스 토큰은 만료(기본 15분)까지 유효하지만, 브라우저에서는 쿠키가 지워져 쓰이지 않는다.
 *
 * 빈으로 등록하지 않는다. SecurityConfig 가 만들어 로그아웃 설정에 붙인다.
 */
class TokenLogoutHandler(
    private val refreshTokenService: RefreshTokenService,
    private val cookies: AuthCookies
) : LogoutHandler {

    override fun logout(request: HttpServletRequest, response: HttpServletResponse, authentication: Authentication?) {
        cookies.readRefresh(request)?.let(refreshTokenService::revokeFamilyOf)
        cookies.clear(response)
    }
}
