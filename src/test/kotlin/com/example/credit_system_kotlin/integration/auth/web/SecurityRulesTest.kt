package com.example.credit_system_kotlin.integration.auth.web

import com.example.credit_system_kotlin.auth.token.AccessTokenService
import com.example.credit_system_kotlin.auth.web.AuthCookies
import com.example.credit_system_kotlin.ledger.repository.LedgerRepository
import com.example.credit_system_kotlin.support.TestTokens
import com.example.credit_system_kotlin.support.withCsrfToken
import com.example.credit_system_kotlin.user.domain.User
import com.example.credit_system_kotlin.user.domain.UserRole
import com.example.credit_system_kotlin.user.repository.UserRepository
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post

/**
 * SecurityConfig 의 규칙이 실제 요청에서 그대로 적용되는지 확인한다.
 * 인증은 [TestTokens] 로 만든 액세스 쿠키나 Bearer 헤더로 한다. CSRF 는 쿠키로 인증한 요청에만 걸린다.
 */
@ActiveProfiles("test")
@AutoConfigureMockMvc
@SpringBootTest
class SecurityRulesTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val userRepository: UserRepository,
    private val ledgerRepository: LedgerRepository,
    accessTokenService: AccessTokenService
) {

    private val tokens = TestTokens(accessTokenService)

    private lateinit var user: User
    private lateinit var admin: User

    @BeforeEach
    fun setUp() {
        user = userRepository.save(User("me", 500L, email = "user@test.local"))
        admin = userRepository.save(User("boss", 0L, email = "admin@test.local", role = UserRole.ADMIN))
    }

    @AfterEach
    fun tearDown() {
        ledgerRepository.deleteAll()
        userRepository.deleteAll()
    }

    @Test
    fun `미인증 api 요청은 리다이렉트 없이 401 JSON 이다`() {
        val result = mockMvc.perform(
            get("/api/users/me/balance").accept(MediaType.TEXT_HTML, MediaType.APPLICATION_JSON)
        ).andReturn()

        assertThat(result.response.status).isEqualTo(401)
        assertThat(result.response.getHeader("Location")).isNull()
        assertThat(errorCode(result)).isEqualTo("UNAUTHENTICATED")
    }

    @Test
    fun `Bearer 헤더로 인증하면 자기 잔액을 본다`() {
        val result = mockMvc.perform(
            get("/api/users/me/balance").header(HttpHeaders.AUTHORIZATION, tokens.bearer(user))
        ).andReturn()

        assertThat(result.response.status).isEqualTo(200)
        assertThat(result.response.contentAsString).contains("500")
    }

    @Test
    fun `쿠키로 인증하면 자기 잔액을 본다`() {
        val result = mockMvc.perform(get("/api/users/me/balance").cookie(tokens.accessCookie(user))).andReturn()

        assertThat(result.response.status).isEqualTo(200)
        assertThat(result.response.contentAsString).contains("500")
    }

    @Test
    fun `우리가 서명하지 않은 토큰은 헤더로 오든 쿠키로 오든 401 이다`() {
        // 모양은 JWT 지만 서명이 우리 키로 된 것이 아니다.
        val forged = tokens.accessToken(user).substringBeforeLast('.') + ".AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"

        val byHeader = mockMvc.perform(get("/api/users/me/balance").header(HttpHeaders.AUTHORIZATION, "Bearer $forged"))
            .andReturn()
        val byCookie = mockMvc.perform(get("/api/users/me/balance").cookie(Cookie(AuthCookies.ACCESS, forged)))
            .andReturn()

        listOf(byHeader, byCookie).forEach {
            assertThat(it.response.status).isEqualTo(401)
            assertThat(errorCode(it)).isEqualTo("UNAUTHENTICATED")
        }
    }

    /**
     * Bearer 요청은 CSRF 검사를 받지 않는다. 그래서 Bearer 헤더가 붙은 요청은 쿠키로 인증되면 안 된다.
     * 그 길이 있으면 다른 사이트가 쓰레기 헤더 하나를 붙여 CSRF 검사를 건너뛰고 피해자의 쿠키로 지급을 부른다.
     */
    @Test
    fun `무효한 Bearer 헤더가 붙으면 유효한 쿠키가 있어도 401 이고 돈이 움직이지 않는다`() {
        val read = mockMvc.perform(
            get("/api/users/me/balance")
                .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-token")
                .cookie(tokens.accessCookie(user))
        ).andReturn()
        val write = mockMvc.perform(
            grant("bearer-cookie-1")
                .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-token")
                .cookie(tokens.accessCookie(admin))
        ).andReturn()

        listOf(read, write).forEach {
            assertThat(it.response.status).isEqualTo(401)
            assertThat(errorCode(it)).isEqualTo("UNAUTHENTICATED")
        }
        assertThat(balanceOfUser()).isEqualTo(500L)
    }

    @Test
    fun `어떤 길로 인증해도 세션이 생기지 않는다`() {
        val requests = listOf(
            get("/api/users/me/balance").header(HttpHeaders.AUTHORIZATION, tokens.bearer(user)),
            get("/api/users/me/balance").cookie(tokens.accessCookie(user)),
            get("/").cookie(tokens.accessCookie(user)),
            get("/api/users/me/balance"),
            get("/"),
            get("/login")
        )

        requests.forEach { request ->
            val result = mockMvc.perform(request).andReturn()

            assertThat(result.request.getSession(false)).`as`(result.request.requestURI).isNull()
            assertThat(result.response.getHeaders(HttpHeaders.SET_COOKIE)).noneMatch { it.contains("JSESSIONID") }
        }
    }

    @Test
    fun `일반 사용자가 운영자 경로에 오면 403 JSON 이다`() {
        val result = mockMvc.perform(get("/api/admin/anything").header(HttpHeaders.AUTHORIZATION, tokens.bearer(user)))
            .andReturn()

        assertThat(result.response.status).isEqualTo(403)
        assertThat(errorCode(result)).isEqualTo("FORBIDDEN")
    }

    /** 주체가 운영자여야 403 이 권한이 아니라 CSRF 때문임이 분명해진다. */
    @Test
    fun `쿠키로 인증한 운영자의 CSRF 토큰 없는 POST 는 403 이고 돈이 움직이지 않는다`() {
        val result = mockMvc.perform(grant("csrf-1").cookie(tokens.accessCookie(admin))).andReturn()

        assertThat(result.response.status).isEqualTo(403)
        assertThat(errorCode(result)).isEqualTo("FORBIDDEN")
        assertThat(balanceOfUser()).isEqualTo(500L)
    }

    @Test
    fun `쿠키로 인증한 운영자가 CSRF 토큰을 실으면 POST 가 통과한다`() {
        val result = mockMvc.perform(grant("csrf-2").cookie(tokens.accessCookie(admin)).withCsrfToken()).andReturn()

        assertThat(result.response.status).isEqualTo(200)
        assertThat(balanceOfUser()).isEqualTo(800L)
    }

    @Test
    fun `Bearer 헤더로 인증한 운영자의 POST 는 CSRF 토큰 없이 통과한다`() {
        val result = mockMvc.perform(
            grant("csrf-3").header(HttpHeaders.AUTHORIZATION, tokens.bearer(admin))
        ).andReturn()

        assertThat(result.response.status).isEqualTo(200)
        assertThat(balanceOfUser()).isEqualTo(800L)
    }

    @Test
    fun `로그아웃은 POST 이고 CSRF 토큰이 필요하다`() {
        val byGet = mockMvc.perform(get("/logout").cookie(tokens.accessCookie(user))).andReturn()
        val withoutToken = mockMvc.perform(post("/logout").cookie(tokens.accessCookie(user))).andReturn()
        val withToken = mockMvc.perform(post("/logout").cookie(tokens.accessCookie(user)).withCsrfToken()).andReturn()

        assertThat(byGet.response.status).isEqualTo(404)
        assertThat(withoutToken.response.status).isEqualTo(403)
        assertThat(withToken.response.status).isEqualTo(302)
        assertThat(withToken.response.redirectedUrl).isEqualTo("/login?logout")
    }

    /** [user] 에게 300 을 지급하는 운영자 요청. 누가 부르는지는 호출하는 쪽이 붙인다. */
    private fun grant(idemKey: String): MockHttpServletRequestBuilder =
        post("/api/admin/users/${user.persistedId}/grants")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"idemKey":"$idemKey","amount":300}""")

    private fun balanceOfUser(): Long = userRepository.findById(user.persistedId).orElseThrow().balance

    private fun errorCode(result: MvcResult): String? =
        Regex("\"code\"\\s*:\\s*\"([A-Z_]+)\"").find(result.response.contentAsString)?.groupValues?.get(1)
}
