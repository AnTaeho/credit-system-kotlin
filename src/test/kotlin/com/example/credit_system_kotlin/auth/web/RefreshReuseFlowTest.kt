package com.example.credit_system_kotlin.auth.web

import com.example.credit_system_kotlin.auth.account.AccountService
import com.example.credit_system_kotlin.auth.token.RefreshTokenRepository
import com.example.credit_system_kotlin.support.FixedMutableClock
import com.example.credit_system_kotlin.support.withCsrfToken
import com.example.credit_system_kotlin.user.repository.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import java.time.Duration
import java.time.Instant

/**
 * 훔친 리프레시 토큰이 쓰인 경우. 이미 회전된 토큰이 유예(10초)를 넘겨 다시 오면 그 로그인의 사슬 전체를 끊는다.
 *
 * 유예를 넘기려면 시간이 흘러야 하므로 시계를 직접 미는 컨텍스트를 따로 띄운다. 유예를 0초로 줄이는 길은
 * 두 요청의 시각이 같게 찍히면 유예 안으로 판정돼 결과가 흔들린다.
 */
@ActiveProfiles("test")
@AutoConfigureMockMvc
@SpringBootTest
class RefreshReuseFlowTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val clock: FixedMutableClock,
    private val accountService: AccountService,
    private val userRepository: UserRepository,
    private val refreshTokenRepository: RefreshTokenRepository
) {

    @TestConfiguration
    class MutableClockConfig {
        @Bean
        @Primary
        fun mutableClock(): FixedMutableClock = FixedMutableClock(Instant.parse("2026-01-01T00:00:00Z"))
    }

    @AfterEach
    fun tearDown() {
        refreshTokenRepository.deleteAll()
        userRepository.findByEmail(EMAIL)?.let(userRepository::delete)
    }

    @Test
    fun `회전된 리프레시가 유예를 넘겨 다시 오면 사슬이 폐기돼 새 리프레시도 쓸 수 없다`() {
        accountService.signUp(EMAIL, PASSWORD)
        val login = mockMvc.perform(post("/login").withCsrfToken().param("email", EMAIL).param("password", PASSWORD))
            .andReturn()
        val stolen = requireNotNull(login.response.getCookie(AuthCookies.REFRESH))
        // 주인이 먼저 갱신해 새 리프레시를 받는다.
        val owner = mockMvc.perform(get("/api/users/me/balance").cookie(stolen)).andReturn()
        val current = requireNotNull(owner.response.getCookie(AuthCookies.REFRESH))
        assertThat(owner.response.status).isEqualTo(200)

        clock.advance(Duration.ofSeconds(11))
        val reuse = mockMvc.perform(get("/api/users/me/balance").cookie(stolen)).andReturn()

        assertThat(reuse.response.status).isEqualTo(401)
        assertThat(reuse.response.getCookie(AuthCookies.REFRESH)?.maxAge).isZero()
        assertThat(reuse.response.getCookie(AuthCookies.ACCESS)?.maxAge).isZero()
        // 주인이 쥔 새 리프레시도 같이 죽는다. 누가 진짜인지 알 수 없으니 둘 다 다시 로그인하게 한다.
        val ownerAgain = mockMvc.perform(get("/api/users/me/balance").cookie(current)).andReturn()
        assertThat(ownerAgain.response.status).isEqualTo(401)
        assertThat(refreshTokenRepository.findAll()).hasSize(2).allMatch { it.revokedAt != null }
    }

    companion object {
        private const val EMAIL = "reuse-alice@test.local"
        private const val PASSWORD = "correct-horse-battery"
    }
}
