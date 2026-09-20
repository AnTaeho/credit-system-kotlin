package com.example.credit_system_kotlin.global.logging

import com.example.credit_system_kotlin.auth.login.AppOidcUser
import com.example.credit_system_kotlin.auth.login.authoritiesFor
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
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import java.time.Instant

/**
 * 요청 ID 가 **시큐리티가 가로챈 응답에도** 붙는지 확인한다.
 *
 * 이것이 [RequestIdFilter] 의 순서를 못 박는 테스트다. 필터가 시큐리티 체인보다 뒤에 있으면
 * 401·403 은 컨트롤러에 닿지 못하고 되돌아가므로 식별자가 없는 채 나간다. 그런데 "왜 튕겼나"를
 * 추적해야 하는 응답이 바로 그것들이라, 순서가 뒤집히면 이 장치의 가장 쓸모 있는 부분이 사라진다.
 *
 * 컨텍스트 조합은 SecurityRulesTest 와 같아 캐시를 같이 쓴다.
 */
@ActiveProfiles("test")
@AutoConfigureMockMvc
@SpringBootTest
class RequestIdSecurityBoundaryTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val userRepository: UserRepository
) {

    private lateinit var user: User

    @BeforeEach
    fun setUp() {
        user = userRepository.save(User("reqid", 500L, email = USER_EMAIL, googleSub = "sub-reqid"))
    }

    @AfterEach
    fun tearDown() {
        userRepository.deleteAll(listOf(user))
    }

    @Test
    fun `미인증 401 응답에도 요청 ID 가 붙는다`() {
        val result = mockMvc.perform(get("/api/users/me/balance")).andReturn()

        assertThat(result.response.status).isEqualTo(401)
        assertThat(result.response.getHeader(RequestIdFilter.HEADER)).isNotNull()
    }

    @Test
    fun `CSRF 토큰 없는 POST 의 403 응답에도 요청 ID 가 붙고 들어온 값을 그대로 돌려준다`() {
        val result = mockMvc.perform(
            post("/api/admin/users/${user.persistedId}/grants")
                .with(oidcLogin().oidcUser(sessionAdmin()))
                .header(RequestIdFilter.HEADER, "csrf-probe-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"idemKey":"reqid-csrf-1","amount":300}""")
        ).andReturn()

        assertThat(result.response.status).isEqualTo(403)
        assertThat(result.response.getHeader(RequestIdFilter.HEADER)).isEqualTo("csrf-probe-1")
    }

    @Test
    fun `정상 응답에도 요청 ID 가 붙는다`() {
        val result = mockMvc.perform(
            get("/api/users/me/balance").header(DEV_HEADER, USER_EMAIL)
        ).andReturn()

        assertThat(result.response.status).isEqualTo(200)
        assertThat(result.response.getHeader(RequestIdFilter.HEADER)).isNotNull()
    }

    private fun sessionAdmin(): AppOidcUser {
        val idToken = OidcIdToken(
            "token",
            Instant.now(),
            Instant.now().plusSeconds(60),
            mapOf("sub" to "sub-reqid-admin", "email" to ADMIN_EMAIL, "email_verified" to true)
        )
        return AppOidcUser(user.persistedId + 1_000L, authoritiesFor(admin = true), idToken, null)
    }

    companion object {
        private const val DEV_HEADER = "X-Dev-User"
        private const val USER_EMAIL = "user@test.local"
        private const val ADMIN_EMAIL = "admin@test.local"
    }
}
