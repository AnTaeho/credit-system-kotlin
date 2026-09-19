package com.example.credit_system_kotlin.web

import com.example.credit_system_kotlin.auth.login.AppOidcUser
import com.example.credit_system_kotlin.auth.login.authoritiesFor
import com.example.credit_system_kotlin.job.domain.Job
import com.example.credit_system_kotlin.job.repository.JobRepository
import com.example.credit_system_kotlin.user.domain.User
import com.example.credit_system_kotlin.user.repository.UserRepository
import jakarta.servlet.RequestDispatcher
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.security.oauth2.core.oidc.OidcIdToken
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import java.time.Instant

/**
 * 화면 경로의 보안 규칙과 렌더링. 세션 로그인 주체는 SecurityRulesTest 와 같이 `oidcLogin()` 으로 만든다.
 * 컨텍스트는 SecurityRulesTest 와 같은 조합이라 캐시를 같이 쓴다.
 */
@ActiveProfiles("test")
@AutoConfigureMockMvc
@SpringBootTest
class PageSecurityTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val userRepository: UserRepository,
    private val jobRepository: JobRepository
) {

    private lateinit var me: User
    private lateinit var other: User
    private lateinit var admin: User

    @BeforeEach
    fun setUp() {
        me = userRepository.save(User("me", 4321L, email = "page-me@test.local", googleSub = "page-sub-me"))
        other = userRepository.save(User("other", 100L, email = "page-other@test.local", googleSub = "page-sub-other"))
        admin = userRepository.save(User("admin", 0L, email = "page-admin@test.local", googleSub = "page-sub-admin"))
    }

    @AfterEach
    fun tearDown() {
        val mine = setOf(me.persistedId, other.persistedId, admin.persistedId)
        jobRepository.deleteAll(jobRepository.findAll().filter { it.userId in mine })
        userRepository.deleteAll(listOf(me, other, admin))
    }

    @Test
    fun `미인증 로그인 화면은 리다이렉트 없이 200 이다`() {
        val result = mockMvc.perform(get("/login").accept(MediaType.TEXT_HTML)).andReturn()

        assertThat(result.response.status).isEqualTo(200)
        assertThat(result.response.contentAsString)
            .contains("구글로 로그인")
            .contains("/oauth2/authorization/google")
    }

    @Test
    fun `미인증 화면 요청은 로그인 화면으로 리다이렉트한다`() {
        listOf("/", "/ledger", "/admin", "/jobs/1").forEach { path ->
            val result = mockMvc.perform(get(path).accept(MediaType.TEXT_HTML)).andReturn()

            assertThat(result.response.status).`as`(path).isEqualTo(302)
            assertThat(result.response.redirectedUrl).`as`(path).endsWith("/login")
        }
    }

    @Test
    fun `정적 리소스는 인증 없이 열린다`() {
        val paths = listOf("/css/app.css", "/js/app.js", "/js/home.js", "/js/job.js", "/js/ledger.js", "/js/admin.js")
        paths.forEach { path ->
            assertThat(mockMvc.perform(get(path)).andReturn().response.status).`as`(path).isEqualTo(200)
        }
    }

    @Test
    fun `미인증 api 요청은 여전히 리다이렉트 없이 401 JSON 이다`() {
        val result = mockMvc.perform(get("/api/jobs").accept(MediaType.TEXT_HTML, MediaType.APPLICATION_JSON))
            .andReturn()

        assertThat(result.response.status).isEqualTo(401)
        assertThat(result.response.getHeader("Location")).isNull()
        assertThat(result.response.contentAsString).contains("\"code\"").contains("UNAUTHENTICATED")
    }

    @Test
    fun `로그인 거부로 돌아오면 거부 안내를 보인다`() {
        val body = mockMvc.perform(get("/login").param("error", "")).andReturn().response.contentAsString

        assertThat(body).contains("허용되지 않은 계정입니다")
    }

    @Test
    fun `로그아웃 뒤와 세션 만료 뒤의 안내를 보인다`() {
        val loggedOut = mockMvc.perform(get("/login").param("logout", "")).andReturn().response.contentAsString
        val expired = mockMvc.perform(get("/login").param("expired", "")).andReturn().response.contentAsString
        val plain = mockMvc.perform(get("/login")).andReturn().response.contentAsString

        assertThat(loggedOut).contains("로그아웃했습니다")
        assertThat(expired).contains("세션이 만료되었습니다")
        assertThat(plain).doesNotContain("허용되지 않은 계정입니다").doesNotContain("로그아웃했습니다")
    }

    @Test
    fun `응답에 CSP 헤더가 있고 같은 출처만 허용한다`() {
        val login = mockMvc.perform(get("/login")).andReturn()
        val home = mockMvc.perform(get("/").with(oidcLogin().oidcUser(principal(me, admin = false)))).andReturn()

        listOf(login, home).forEach {
            assertThat(it.response.getHeader("Content-Security-Policy"))
                .contains("default-src 'self'")
                .contains("script-src 'self'")
                .doesNotContain("unsafe-inline")
        }
    }

    @Test
    fun `로그인한 홈은 잔액과 이메일과 CSRF meta 를 보인다`() {
        val result = asMe(get("/"))

        assertThat(result.response.status).isEqualTo(200)
        assertThat(result.response.contentAsString)
            .contains("4321")
            .contains("page-me@test.local")
            .contains("name=\"_csrf\"")
            .contains("name=\"_csrf_header\"")
            .contains("action=\"/logout\"")
    }

    @Test
    fun `홈은 내 job 만 보이고 결과 URL 은 이미지가 아니라 텍스트다`() {
        val mine = jobRepository.save(Job.hold(me.persistedId, 100L, "my cat"))
        jobRepository.save(Job.hold(other.persistedId, 100L, "someone else's dog"))

        val body = asMe(get("/")).response.contentAsString

        assertThat(body).contains("my cat").contains("/jobs/${mine.persistedId}").doesNotContain("someone else's dog")
        assertThat(body).doesNotContain("<img")
    }

    @Test
    fun `job 상세는 내 것이면 200, 남의 것·없는 것·숫자가 아닌 id 는 404 화면이다`() {
        val mine = jobRepository.save(Job.hold(me.persistedId, 100L, "my cat"))
        val theirs = jobRepository.save(Job.hold(other.persistedId, 100L, "their dog"))

        val own = asMe(get("/jobs/${mine.persistedId}"))
        assertThat(own.response.status).isEqualTo(200)
        assertThat(own.response.contentAsString).contains("my cat").contains("HOLDING")

        listOf("/jobs/${theirs.persistedId}", "/jobs/999999999", "/jobs/abc").forEach { path ->
            val result = asMe(get(path))
            assertThat(result.response.status).`as`(path).isEqualTo(404)
            assertThat(result.response.contentType).`as`(path).startsWith(MediaType.TEXT_HTML_VALUE)
            assertThat(result.response.contentAsString).`as`(path)
                .contains("찾을 수 없습니다")
                .doesNotContain("their dog")
        }
    }

    @Test
    fun `prompt 의 스크립트는 화면에 이스케이프되어 나온다`() {
        val payload = "<script>alert('xss')</script><img src=x onerror=alert(1)>"
        val job = jobRepository.save(Job.hold(me.persistedId, 100L, payload))

        listOf("/", "/jobs/${job.persistedId}").forEach { path ->
            val body = asMe(get(path)).response.contentAsString
            assertThat(body).`as`(path)
                .contains("&lt;script&gt;alert(")
                .doesNotContain("<script>alert")
                .doesNotContain("<img src=x")
        }
    }

    @Test
    fun `원장 화면이 렌더링된다`() {
        val result = asMe(get("/ledger"))

        assertThat(result.response.status).isEqualTo(200)
        assertThat(result.response.contentAsString).contains("원장").contains("4321")
    }

    @Test
    fun `운영자 화면은 일반 사용자에게 403 이다`() {
        val result = asMe(get("/admin").accept(MediaType.TEXT_HTML))

        assertThat(result.response.status).isEqualTo(403)
    }

    @Test
    fun `운영자는 운영자 화면에서 자기 userId 를 본다`() {
        val result = mockMvc.perform(get("/admin").with(oidcLogin().oidcUser(principal(admin, admin = true))))
            .andReturn()

        assertThat(result.response.status).isEqualTo(200)
        assertThat(result.response.contentAsString)
            .contains("id=\"my-user-id\">${admin.persistedId}<")
            .contains("grant-form")
    }

    @Test
    fun `일반 사용자 화면에는 운영자 메뉴가 없다`() {
        assertThat(asMe(get("/")).response.contentAsString).doesNotContain("href=\"/admin\"")
        val adminHome = mockMvc.perform(get("/").with(oidcLogin().oidcUser(principal(admin, admin = true))))
            .andReturn()
        assertThat(adminHome.response.contentAsString).contains("href=\"/admin\"")
    }

    @Test
    fun `오류 화면 템플릿이 렌더링된다`() {
        mapOf(403 to "권한이 없습니다", 404 to "찾을 수 없습니다", 500 to "오류가 발생했습니다").forEach { (status, text) ->
            val result = mockMvc.perform(
                get("/error")
                    .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, status)
                    .requestAttr(RequestDispatcher.ERROR_REQUEST_URI, "/somewhere")
                    .accept(MediaType.TEXT_HTML)
            ).andReturn()

            assertThat(result.response.status).`as`("$status").isEqualTo(status)
            assertThat(result.response.contentAsString).`as`("$status").contains(text)
        }
    }

    private fun asMe(request: MockHttpServletRequestBuilder): MvcResult =
        mockMvc.perform(request.with(oidcLogin().oidcUser(principal(me, admin = false)))).andReturn()

    private fun principal(user: User, admin: Boolean): AppOidcUser {
        val idToken = OidcIdToken(
            "token",
            Instant.now(),
            Instant.now().plusSeconds(60),
            mapOf("sub" to user.googleSub!!, "email" to user.email!!, "email_verified" to true)
        )
        return AppOidcUser(user.persistedId, authoritiesFor(admin = admin), idToken, null)
    }
}
