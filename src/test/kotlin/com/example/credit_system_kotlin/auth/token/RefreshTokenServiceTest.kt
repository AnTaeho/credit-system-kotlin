package com.example.credit_system_kotlin.auth.token

import com.example.credit_system_kotlin.auth.config.JwtProperties
import com.example.credit_system_kotlin.support.FixedMutableClock
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.test.context.ActiveProfiles
import java.time.Duration
import java.time.Instant

@ActiveProfiles("test")
@DataJpaTest
class RefreshTokenServiceTest @Autowired constructor(
    private val refreshTokenRepository: RefreshTokenRepository
) {

    private val clock = FixedMutableClock(Instant.parse("2026-01-01T00:00:00Z"))

    private val service = RefreshTokenService(
        refreshTokenRepository,
        JwtProperties(secret = "unit-test-secret-0123456789-abcdefghij", refreshTtl = Duration.ofDays(14)),
        clock
    )

    @Test
    fun `발급하면 DB 에는 원문이 없고 해시만 남는다`() {
        val raw = service.issue(USER_ID)

        val saved = refreshTokenRepository.findAll().single()
        assertThat(saved.tokenHash).isNotEqualTo(raw).hasSize(64).isEqualTo(RefreshTokenService.sha256Hex(raw))
        assertThat(saved.userId).isEqualTo(USER_ID)
        assertThat(saved.expiresAt).isEqualTo(clock.instant().plus(Duration.ofDays(14)))
    }

    @Test
    fun `로그인마다 다른 토큰이 나온다`() {
        val first = service.issue(USER_ID)
        val second = service.issue(USER_ID)

        assertThat(first).isNotEqualTo(second)
        assertThat(refreshTokenRepository.count()).isEqualTo(2)
    }

    @Test
    fun `유효한 토큰은 주인의 사용자 id 를 준다`() {
        val raw = service.issue(USER_ID)
        val other = service.issue(OTHER_USER_ID)

        assertThat(service.userIdOf(raw)).isEqualTo(USER_ID)
        assertThat(service.userIdOf(other)).isEqualTo(OTHER_USER_ID)
    }

    @Test
    fun `같은 토큰을 여러 번 써도 계속 유효하고 새 토큰이 생기지 않는다`() {
        val raw = service.issue(USER_ID)

        repeat(3) {
            clock.advance(Duration.ofDays(1))
            assertThat(service.userIdOf(raw)).isEqualTo(USER_ID)
        }
        assertThat(refreshTokenRepository.findAll().single().tokenHash).isEqualTo(RefreshTokenService.sha256Hex(raw))
    }

    @Test
    fun `만료 직전까지는 유효하고 만료 시각부터는 null 이며 행은 정리 작업이 지울 때까지 남는다`() {
        val raw = service.issue(USER_ID)

        clock.advance(Duration.ofDays(14).minusSeconds(1))
        assertThat(service.userIdOf(raw)).isEqualTo(USER_ID)
        clock.advance(Duration.ofSeconds(1))
        assertThat(service.userIdOf(raw)).isNull()
        assertThat(refreshTokenRepository.count()).isEqualTo(1)
    }

    @Test
    fun `모르는 토큰은 null 이고 지워도 다른 토큰에 영향이 없다`() {
        val raw = service.issue(USER_ID)

        assertThat(service.userIdOf("never-issued")).isNull()
        service.delete("never-issued")

        assertThat(service.userIdOf(raw)).isEqualTo(USER_ID)
    }

    @Test
    fun `로그아웃으로 지운 토큰은 null 이고 같은 사용자의 다른 로그인은 살아 있다`() {
        val raw = service.issue(USER_ID)
        val otherLogin = service.issue(USER_ID)

        service.delete(raw)

        assertThat(service.userIdOf(raw)).isNull()
        assertThat(service.userIdOf(otherLogin)).isEqualTo(USER_ID)
        assertThat(refreshTokenRepository.count()).isEqualTo(1)
    }

    @Test
    fun `한 사용자의 토큰을 모두 지워도 다른 사용자의 토큰은 남는다`() {
        val first = service.issue(USER_ID)
        val second = service.issue(USER_ID)
        val bystander = service.issue(OTHER_USER_ID)

        val deleted = refreshTokenRepository.deleteAllOfUser(USER_ID)

        assertThat(deleted).isEqualTo(2)
        assertThat(service.userIdOf(first)).isNull()
        assertThat(service.userIdOf(second)).isNull()
        assertThat(service.userIdOf(bystander)).isEqualTo(OTHER_USER_ID)
    }

    companion object {
        /** 토큰은 사용자 행을 참조만 하므로(FK 없음) 실제 행이 없어도 된다. */
        private const val USER_ID = 1L
        private const val OTHER_USER_ID = 2L
    }
}
