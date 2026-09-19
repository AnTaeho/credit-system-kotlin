package com.example.credit_system_kotlin.web

import com.example.credit_system_kotlin.auth.login.DevLoginUser
import com.example.credit_system_kotlin.user.repository.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.core.context.SecurityContext
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post

/**
 * 화면의 개발 로그인(`POST /dev-login`). 헤더 개발 로그인과 달리 **세션에** 인증을 저장하므로 CSRF 가 필요하다.
 * test 프로필은 개발 로그인이 켜져 있다. 꺼진 경우는 [DevSessionLoginDisabledTest].
 */
@ActiveProfiles("test")
@AutoConfigureMockMvc
@SpringBootTest
class DevSessionLoginTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val userRepository: UserRepository
) {

    @BeforeEach
    fun setUp() = removeDevUsers()

    @AfterEach
    fun tearDown() = removeDevUsers()

    private fun removeDevUsers() {
        listOf(USER_EMAIL, ADMIN_EMAIL).mapNotNull(userRepository::findByEmail).forEach(userRepository::delete)
    }

    @Test
    fun `로그인 화면에 개발 로그인 폼이 CSRF hidden 필드와 함께 보인다`() {
        val body = mockMvc.perform(get("/login")).andReturn().response.contentAsString

        assertThat(body).contains("action=\"/dev-login\"").contains("name=\"_csrf\"").contains(USER_EMAIL)
    }

    @Test
    fun `CSRF 토큰을 실은 개발 로그인은 세션에 인증을 저장하고 홈이 열린다`() {
        val login = mockMvc.perform(post("/dev-login").param("email", "User@Test.Local").with(csrf())).andReturn()

        assertThat(login.response.status).isEqualTo(302)
        assertThat(login.response.redirectedUrl).isEqualTo("/")
        val session = login.request.getSession(false) as MockHttpSession
        val context = session.getAttribute(SECURITY_CONTEXT_KEY) as SecurityContext
        assertThat((context.authentication!!.principal as DevLoginUser).email).isEqualTo(USER_EMAIL)

        val home = mockMvc.perform(get("/").session(session)).andReturn()
        assertThat(home.response.status).isEqualTo(200)
        assertThat(home.response.contentAsString).contains(USER_EMAIL)

        // 세션만으로(헤더 없이) API 도 된다.
        val balance = mockMvc.perform(get("/api/users/me/balance").session(session)).andReturn()
        assertThat(balance.response.status).isEqualTo(200)
    }

    @Test
    fun `개발 로그인 운영자는 세션으로 운영자 화면에 들어간다`() {
        val login = mockMvc.perform(post("/dev-login").param("email", ADMIN_EMAIL).with(csrf())).andReturn()
        val session = login.request.getSession(false) as MockHttpSession

        val admin = mockMvc.perform(get("/admin").session(session)).andReturn()

        assertThat(admin.response.status).isEqualTo(200)
    }

    @Test
    fun `CSRF 토큰 없는 개발 로그인은 403 이고 사용자 행을 만들지 않는다`() {
        val result = mockMvc.perform(post("/dev-login").param("email", USER_EMAIL)).andReturn()

        assertThat(result.response.status).isEqualTo(403)
        assertThat(userRepository.findByEmail(USER_EMAIL)).isNull()
        assertThat(result.request.getSession(false)?.getAttribute(SECURITY_CONTEXT_KEY)).isNull()
    }

    @Test
    fun `허용 목록 밖 이메일은 로그인 거부 안내로 돌아가고 인증도 사용자 행도 남지 않는다`() {
        val result = mockMvc.perform(post("/dev-login").param("email", "stranger@evil.test").with(csrf())).andReturn()

        assertThat(result.response.status).isEqualTo(302)
        assertThat(result.response.redirectedUrl).isEqualTo("/login?error")
        assertThat(userRepository.findByEmail("stranger@evil.test")).isNull()
        assertThat(result.request.getSession(false)?.getAttribute(SECURITY_CONTEXT_KEY)).isNull()
    }

    @Test
    fun `이메일이 비어 있으면 로그인 거부 안내로 돌아간다`() {
        val result = mockMvc.perform(post("/dev-login").param("email", " ").with(csrf())).andReturn()

        assertThat(result.response.redirectedUrl).isEqualTo("/login?error")
    }

    companion object {
        private const val SECURITY_CONTEXT_KEY = "SPRING_SECURITY_CONTEXT"
        private const val USER_EMAIL = "user@test.local"
        private const val ADMIN_EMAIL = "admin@test.local"
    }
}
