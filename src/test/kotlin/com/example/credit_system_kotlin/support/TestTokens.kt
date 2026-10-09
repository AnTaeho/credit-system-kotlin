package com.example.credit_system_kotlin.support

import com.example.credit_system_kotlin.auth.token.AccessTokenService
import com.example.credit_system_kotlin.auth.web.AuthCookies
import com.example.credit_system_kotlin.user.domain.User
import jakarta.servlet.http.Cookie

/**
 * 테스트가 로그인한 요청을 만들 때 쓴다. 실제 [AccessTokenService] 로 액세스 JWT 를 내므로 운영과 같은 필터를 거친다.
 * Bearer 헤더는 CSRF 검사를 받지 않고, 액세스 쿠키로 보내는 POST 에는 CSRF 토큰이 있어야 한다.
 */
class TestTokens(private val accessTokenService: AccessTokenService) {

    fun accessToken(user: User): String = accessTokenService.issue(user.persistedId, user.role)

    fun bearer(user: User): String = BEARER + accessToken(user)

    fun accessCookie(user: User): Cookie = Cookie(AuthCookies.ACCESS, accessToken(user))

    companion object {
        private const val BEARER = "Bearer "
    }
}
