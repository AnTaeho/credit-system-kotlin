package com.example.credit_system_kotlin.auth.web

import com.example.credit_system_kotlin.auth.token.AccessTokenService
import com.example.credit_system_kotlin.auth.token.RefreshTokenService
import com.example.credit_system_kotlin.user.domain.User
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component

@Component
class TokenLogin(
    private val accessTokenService: AccessTokenService,
    private val refreshTokenService: RefreshTokenService,
    private val cookies: AuthCookies
) {

    fun logIn(user: User, response: HttpServletResponse) {
        val userId = user.persistedId
        // 리프레시를 먼저 낸다. Redis 저장이 실패하면 쿠키를 하나도 쓰지 않은 채 예외가 올라간다.
        val refreshToken = refreshTokenService.issue(userId)
        cookies.writeAccess(response, accessTokenService.issue(userId, user.role))
        cookies.writeRefresh(response, refreshToken)
    }
}
