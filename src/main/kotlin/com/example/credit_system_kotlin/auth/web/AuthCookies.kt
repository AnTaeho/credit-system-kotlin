package com.example.credit_system_kotlin.auth.web

import com.example.credit_system_kotlin.auth.config.AuthProperties
import com.example.credit_system_kotlin.auth.config.JwtProperties
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseCookie
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * 로그인 쿠키 두 개를 쓰고 읽고 지운다.
 *
 * - `credit_at`: 액세스 JWT. 수명은 토큰과 같다.
 * - `credit_rt`: 리프레시 토큰 원문. 수명은 토큰과 같다.
 *
 * 둘 다 `HttpOnly`(스크립트가 읽지 못한다), `SameSite=Lax`(다른 사이트의 POST·fetch 에 따라가지 않는다),
 * `Path=/` 이다. `Secure` 는 HTTPS 인 운영에서만 붙인다(`app.auth.cookie-secure`).
 * 지울 때도 같은 이름·경로로 `Max-Age=0` 을 보낸다. 속성이 다르면 브라우저가 다른 쿠키로 본다.
 */
@Component
class AuthCookies(
    private val authProperties: AuthProperties,
    private val jwtProperties: JwtProperties
) {

    fun writeAccess(response: HttpServletResponse, accessToken: String) =
        write(response, ACCESS, accessToken, jwtProperties.accessTtl)

    fun writeRefresh(response: HttpServletResponse, refreshRaw: String) =
        write(response, REFRESH, refreshRaw, jwtProperties.refreshTtl)

    fun clear(response: HttpServletResponse) {
        write(response, ACCESS, "", Duration.ZERO)
        write(response, REFRESH, "", Duration.ZERO)
    }

    fun readAccess(request: HttpServletRequest): String? = read(request, ACCESS)

    fun readRefresh(request: HttpServletRequest): String? = read(request, REFRESH)

    private fun read(request: HttpServletRequest, name: String): String? =
        request.cookies?.firstOrNull { it.name == name }?.value?.takeIf { it.isNotBlank() }

    private fun write(response: HttpServletResponse, name: String, value: String, maxAge: Duration) {
        val cookie = ResponseCookie.from(name, value)
            .httpOnly(true)
            .secure(authProperties.cookieSecure)
            .sameSite(SAME_SITE)
            .path("/")
            .maxAge(maxAge)
            .build()
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString())
    }

    companion object {
        const val ACCESS = "credit_at"
        const val REFRESH = "credit_rt"
        const val SAME_SITE = "Lax"
    }
}
