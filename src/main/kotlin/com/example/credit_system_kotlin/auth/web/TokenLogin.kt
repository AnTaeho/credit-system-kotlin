package com.example.credit_system_kotlin.auth.web

import com.example.credit_system_kotlin.auth.token.AccessTokenService
import com.example.credit_system_kotlin.auth.token.RefreshTokenService
import com.example.credit_system_kotlin.user.domain.User
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component

/** 비밀번호 확인을 통과한 사람을 브라우저에 로그인시킨다. 액세스와 리프레시를 새로 내고 쿠키로 심는다. */
@Component
class TokenLogin(
    private val accessTokenService: AccessTokenService,
    private val refreshTokenService: RefreshTokenService,
    private val cookies: AuthCookies
) {

    fun logIn(user: User, response: HttpServletResponse) {
        val userId = user.persistedId
        cookies.writeAccess(response, accessTokenService.issue(userId, user.role))
        cookies.writeRefresh(response, refreshTokenService.issue(userId))
    }
}
