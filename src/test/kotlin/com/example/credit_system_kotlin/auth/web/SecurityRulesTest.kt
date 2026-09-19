package com.example.credit_system_kotlin.auth.web

import com.example.credit_system_kotlin.auth.login.AppOidcUser
import com.example.credit_system_kotlin.auth.login.authoritiesFor
import com.example.credit_system_kotlin.ledger.repository.LedgerRepository
import com.example.credit_system_kotlin.user.domain.User
import com.example.credit_system_kotlin.user.repository.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.security.oauth2.core.oidc.OidcIdToken
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import java.time.Instant

/**
 * SecurityConfig 의 규칙이 실제 요청에서 그대로 적용되는지 확인한다.
 *
 * 구글 로그인 주체는 spring-security-test 의 `oidcLogin()` 으로 만든다. 실제 로그인 성공 뒤
 * 세션에 들어가는 것과 같은 [AppOidcUser] 를 넣어, 쿠키(세션)로 인증된 요청을 흉내 낸다.
 */
@ActiveProfiles("test")
@AutoConfigureMockMvc
@SpringBootTest
class SecurityRulesTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val userRepository: UserRepository,
    private val ledgerRepository: LedgerRepository
) {

    private lateinit var user: User

    @BeforeEach
    fun setUp() {
        user = userRepository.save(User("me", 500L, email = USER_EMAIL, googleSub = "sub-me"))
    }

    @AfterEach
    fun tearDown() {
        ledgerRepository.deleteAll()
        userRepository.deleteAll()
    }

    @Test
    fun `미인증 api 요청은 리다이렉트 없이 401 JSON 이다`() {
        val result = mockMvc.perform(get("/api/users/me/balance")).andReturn()

        assertThat(result.response.status).isEqualTo(401)
        assertThat(result.response.getHeader("Location")).isNull()
        assertThat(errorCode(result)).isEqualTo("UNAUTHENTICATED")
    }

    @Test
    fun `허용 목록 밖 이메일의 개발 로그인은 401 이고 사용자 행을 만들지 않는다`() {
        val before = userRepository.count()

        val result = mockMvc.perform(get("/api/users/me/balance").header(DEV_HEADER, "stranger@evil.test")).andReturn()

        assertThat(result.response.status).isEqualTo(401)
        assertThat(errorCode(result)).isEqualTo("UNAUTHENTICATED")
        assertThat(userRepository.count()).isEqualTo(before)
    }

    @Test
    fun `개발 로그인 이메일은 대소문자를 가리지 않는다`() {
        val result = mockMvc.perform(get("/api/users/me/balance").header(DEV_HEADER, "User@Test.Local")).andReturn()

        assertThat(result.response.status).isEqualTo(200)
        assertThat(result.response.contentAsString).contains("500")
    }

    @Test
    fun `개발 로그인 인증은 세션에 남지 않는다`() {
        val first = mockMvc.perform(get("/api/users/me/balance").header(DEV_HEADER, USER_EMAIL)).andReturn()
        assertThat(first.response.status).isEqualTo(200)

        val session = first.request.getSession(false)
        assertThat(session?.getAttribute(SECURITY_CONTEXT_KEY)).isNull()
    }

    @Test
    fun `일반 사용자가 운영자 경로에 오면 403 JSON 이다`() {
        val result = mockMvc.perform(get("/api/admin/anything").header(DEV_HEADER, USER_EMAIL)).andReturn()

        assertThat(result.response.status).isEqualTo(403)
        assertThat(errorCode(result)).isEqualTo("FORBIDDEN")
    }

    @Test
    fun `운영자는 운영자 경로의 권한 검사를 통과한다`() {
        // 운영자 경로는 아직 없다(9-C). 권한 검사를 통과하면 컨트롤러가 없어 404 가 난다.
        val result = mockMvc.perform(get("/api/admin/anything").header(DEV_HEADER, ADMIN_EMAIL)).andReturn()

        assertThat(result.response.status).isEqualTo(404)
    }

    @Test
    fun `세션 로그인 사용자는 자기 잔액을 본다`() {
        val result = mockMvc.perform(get("/api/users/me/balance").with(oidcLogin().oidcUser(sessionUser()))).andReturn()

        assertThat(result.response.status).isEqualTo(200)
        assertThat(result.response.contentAsString).contains("500")
    }

    @Test
    fun `세션 로그인 사용자의 CSRF 토큰 없는 POST 는 403 이고 돈이 움직이지 않는다`() {
        val result = mockMvc.perform(
            post("/api/users/me/charge")
                .with(oidcLogin().oidcUser(sessionUser()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"idemKey":"csrf-1","amount":300}""")
        ).andReturn()

        assertThat(result.response.status).isEqualTo(403)
        assertThat(errorCode(result)).isEqualTo("FORBIDDEN")
        assertThat(userRepository.findById(user.persistedId).orElseThrow().balance).isEqualTo(500L)
    }

    @Test
    fun `세션 로그인 사용자가 CSRF 토큰을 실으면 POST 가 통과한다`() {
        val result = mockMvc.perform(
            post("/api/users/me/charge")
                .with(oidcLogin().oidcUser(sessionUser()))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"idemKey":"csrf-2","amount":300}""")
        ).andReturn()

        assertThat(result.response.status).isEqualTo(200)
        assertThat(userRepository.findById(user.persistedId).orElseThrow().balance).isEqualTo(800L)
    }

    @Test
    fun `api 밖의 경로는 미인증 브라우저 요청이면 로그인으로 리다이렉트한다`() {
        val result = mockMvc.perform(get("/").accept(MediaType.TEXT_HTML)).andReturn()

        assertThat(result.response.status).isEqualTo(302)
        assertThat(result.response.redirectedUrl).contains("/oauth2/authorization/google")
    }

    @Test
    fun `로그아웃은 POST 이고 CSRF 토큰이 필요하다`() {
        val withoutToken = mockMvc.perform(post("/logout").with(oidcLogin().oidcUser(sessionUser()))).andReturn()
        val withToken = mockMvc.perform(post("/logout").with(oidcLogin().oidcUser(sessionUser())).with(csrf()))
            .andReturn()

        assertThat(withoutToken.response.status).isEqualTo(403)
        assertThat(withToken.response.status).isEqualTo(302)
    }

    private fun sessionUser(): AppOidcUser {
        val idToken = OidcIdToken(
            "token",
            Instant.now(),
            Instant.now().plusSeconds(60),
            mapOf("sub" to "sub-me", "email" to USER_EMAIL, "email_verified" to true)
        )
        return AppOidcUser(user.persistedId, authoritiesFor(admin = false), idToken, null)
    }

    private fun errorCode(result: MvcResult): String? =
        Regex("\"code\"\\s*:\\s*\"([A-Z_]+)\"").find(result.response.contentAsString)?.groupValues?.get(1)

    companion object {
        private const val SECURITY_CONTEXT_KEY = "SPRING_SECURITY_CONTEXT"
        private const val DEV_HEADER = "X-Dev-User"
        private const val USER_EMAIL = "user@test.local"
        private const val ADMIN_EMAIL = "admin@test.local"
    }
}
