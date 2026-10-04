package com.example.credit_system_kotlin.support

import com.example.credit_system_kotlin.auth.token.AccessTokenService
import com.example.credit_system_kotlin.auth.web.AuthCookies
import com.example.credit_system_kotlin.user.domain.User
import com.example.credit_system_kotlin.user.domain.UserRole
import jakarta.servlet.http.Cookie
import org.springframework.http.HttpHeaders

/**
 * 테스트가 "이 사람으로 로그인한 요청"을 만들 때 쓴다. 실제 [AccessTokenService] 로 액세스 JWT 를 내므로
 * 요청은 운영과 같은 필터를 거쳐 인증된다.
 *
 * - Bearer 헤더: curl 이 쓰는 길. CSRF 검사를 받지 않는다.
 * - 액세스 쿠키: 브라우저가 쓰는 길. POST 에는 CSRF 토큰이 있어야 한다.
 *
 * 액세스 토큰이 유효한 요청은 사용자 행을 보지 않는다. 핸들러가 사용자 행을 읽지 않는 경로(액추에이터 등)는
 * 행 없이 id 와 역할만으로 토큰을 만들어도 된다.
 */
class TestTokens(private val accessTokenService: AccessTokenService) {

    fun accessToken(user: User): String = accessToken(user.persistedId, user.role)

    fun accessToken(userId: Long, role: UserRole): String = accessTokenService.issue(userId, role)

    fun bearer(user: User): String = BEARER + accessToken(user)

    fun bearer(userId: Long, role: UserRole): String = BEARER + accessToken(userId, role)

    fun bearerHeaders(user: User): HttpHeaders = HttpHeaders().apply { add(HttpHeaders.AUTHORIZATION, bearer(user)) }

    fun accessCookie(user: User): Cookie = Cookie(AuthCookies.ACCESS, accessToken(user))

    companion object {
        private const val BEARER = "Bearer "
    }
}
