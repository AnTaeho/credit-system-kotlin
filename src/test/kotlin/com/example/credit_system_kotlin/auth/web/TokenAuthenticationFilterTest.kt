package com.example.credit_system_kotlin.auth.web

import com.example.credit_system_kotlin.auth.config.AuthProperties
import com.example.credit_system_kotlin.auth.config.JwtProperties
import com.example.credit_system_kotlin.auth.token.AccessTokenService
import com.example.credit_system_kotlin.auth.token.RefreshCheck
import com.example.credit_system_kotlin.auth.token.RefreshTokenService
import com.example.credit_system_kotlin.user.repository.UserRepository
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.http.HttpHeaders
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder

// 리프레시 확인 결과에 따라 필터가 쿠키를 어떻게 다루는지 본다. Redis 불통은 실물로 만들기 어려워 서비스를 흉내 낸다.
class TokenAuthenticationFilterTest {

    private lateinit var accessTokenService: AccessTokenService
    private lateinit var refreshTokenService: RefreshTokenService
    private lateinit var userRepository: UserRepository
    private lateinit var filter: TokenAuthenticationFilter

    @BeforeEach
    fun setUp() {
        accessTokenService = mock()
        refreshTokenService = mock()
        userRepository = mock()
        val cookies = AuthCookies(AuthProperties(), JwtProperties(secret = "unit-test-secret-0123456789-abcdefghij"))
        filter = TokenAuthenticationFilter(accessTokenService, refreshTokenService, userRepository, cookies)
    }

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `리프레시를 확인할 수 없으면 미인증이고 두 쿠키를 지우지 않는다`() {
        whenever(refreshTokenService.check(REFRESH)) doReturn RefreshCheck.Unavailable

        val (response, authentication) =
            runFilter(Cookie(AuthCookies.ACCESS, "expired"), Cookie(AuthCookies.REFRESH, REFRESH))

        assertThat(authentication).isNull()
        assertThat(response.getHeaders(HttpHeaders.SET_COOKIE)).isEmpty()
        verify(userRepository, never()).findById(any())
        verify(accessTokenService, never()).issue(any(), any())
    }

    // 위와 견주는 경우다. 무효로 판정된 리프레시는 쿠키를 지운다.
    @Test
    fun `리프레시가 무효면 미인증이고 두 쿠키를 지운다`() {
        whenever(refreshTokenService.check(REFRESH)) doReturn RefreshCheck.Invalid

        val (response, authentication) = runFilter(Cookie(AuthCookies.REFRESH, REFRESH))

        assertThat(authentication).isNull()
        listOf(AuthCookies.ACCESS, AuthCookies.REFRESH).forEach { name ->
            val cookie = response.getCookie(name)
            assertThat(cookie).`as`(name).isNotNull()
            assertThat(cookie!!.value).`as`(name).isEmpty()
            assertThat(cookie.maxAge).`as`(name).isZero()
        }
    }

    // 필터를 돌리고, 체인에 닿은 시점의 인증을 응답과 함께 돌려준다.
    private fun runFilter(vararg cookies: Cookie): Pair<MockHttpServletResponse, Authentication?> {
        val request = MockHttpServletRequest("GET", "/api/users/me/balance").apply { setCookies(*cookies) }
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        filter.doFilter(request, response, chain)

        assertThat(chain.request).`as`("요청은 다음 필터로 넘어간다").isNotNull()
        return response to SecurityContextHolder.getContext().authentication
    }

    companion object {
        private const val REFRESH = "refresh-jwt"
    }
}
