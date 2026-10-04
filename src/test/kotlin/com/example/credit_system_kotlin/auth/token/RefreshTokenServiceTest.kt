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
        JwtProperties(
            secret = "unit-test-secret-0123456789-abcdefghij",
            refreshTtl = Duration.ofDays(14),
            refreshReuseGrace = Duration.ofSeconds(10)
        ),
        clock
    )

    @Test
    fun `발급하면 DB 에는 원문이 없고 해시만 남는다`() {
        val raw = service.issue(USER_ID)

        val saved = refreshTokenRepository.findAll().single()
        assertThat(saved.tokenHash).isNotEqualTo(raw).hasSize(64).isEqualTo(RefreshTokenService.sha256Hex(raw))
        assertThat(saved.familyId).isNotEqualTo(raw)
        assertThat(saved.userId).isEqualTo(USER_ID)
        assertThat(saved.expiresAt).isEqualTo(clock.instant().plus(Duration.ofDays(14)))
        assertThat(saved.rotatedAt).isNull()
        assertThat(saved.revokedAt).isNull()
    }

    @Test
    fun `로그인마다 다른 사슬이 열린다`() {
        service.issue(USER_ID)
        service.issue(USER_ID)

        assertThat(refreshTokenRepository.findAll().map { it.familyId }.distinct()).hasSize(2)
    }

    @Test
    fun `회전하면 같은 사슬의 새 토큰이 나오고 새 토큰으로 다시 회전할 수 있다`() {
        val first = service.issue(USER_ID)

        val rotated = service.rotate(first) as RotationResult.Rotated

        assertThat(rotated.userId).isEqualTo(USER_ID)
        assertThat(rotated.newRaw).isNotEqualTo(first)
        assertThat(refreshTokenRepository.findAll().map { it.familyId }.distinct()).hasSize(1)
        assertThat(service.rotate(rotated.newRaw)).isInstanceOf(RotationResult.Rotated::class.java)
    }

    @Test
    fun `이미 회전된 토큰이 유예 안에 다시 오면 WithinGrace 이고 새 토큰은 계속 쓸 수 있다`() {
        val first = service.issue(USER_ID)
        val rotated = service.rotate(first) as RotationResult.Rotated

        clock.advance(Duration.ofSeconds(10))

        assertThat(service.rotate(first)).isEqualTo(RotationResult.WithinGrace(USER_ID))
        assertThat(refreshTokenRepository.count()).`as`("유예 안에서는 새 토큰을 만들지 않는다").isEqualTo(2)
        assertThat(service.rotate(rotated.newRaw)).isInstanceOf(RotationResult.Rotated::class.java)
    }

    @Test
    fun `유예를 넘겨 다시 오면 ReuseDetected 이고 사슬 전체가 폐기돼 새 토큰도 거절된다`() {
        val first = service.issue(USER_ID)
        val rotated = service.rotate(first) as RotationResult.Rotated
        val otherLogin = service.issue(USER_ID)

        clock.advance(Duration.ofSeconds(11))

        assertThat(service.rotate(first)).isEqualTo(RotationResult.ReuseDetected)
        assertThat(service.rotate(rotated.newRaw)).isEqualTo(RotationResult.Rejected)
        assertThat(service.rotate(first)).`as`("폐기된 뒤에는 유예도 없다").isEqualTo(RotationResult.Rejected)
        assertThat(service.rotate(otherLogin)).`as`("다른 로그인의 사슬은 살아 있다")
            .isInstanceOf(RotationResult.Rotated::class.java)
    }

    @Test
    fun `만료된 토큰은 Rejected 이다`() {
        val raw = service.issue(USER_ID)

        clock.advance(Duration.ofDays(14))

        assertThat(service.rotate(raw)).isEqualTo(RotationResult.Rejected)
        assertThat(refreshTokenRepository.findAll().single().revokedAt).`as`("만료는 탈취 신호가 아니다").isNull()
    }

    @Test
    fun `revokeFamilyOf 뒤에는 그 사슬의 어떤 토큰도 Rejected 이다`() {
        val first = service.issue(USER_ID)
        val rotated = service.rotate(first) as RotationResult.Rotated

        service.revokeFamilyOf(first)

        assertThat(service.rotate(rotated.newRaw)).isEqualTo(RotationResult.Rejected)
        assertThat(service.rotate(first)).isEqualTo(RotationResult.Rejected)
    }

    @Test
    fun `모르는 토큰은 Rejected 이고 폐기 요청은 조용히 무시된다`() {
        val raw = service.issue(USER_ID)

        assertThat(service.rotate("never-issued")).isEqualTo(RotationResult.Rejected)
        service.revokeFamilyOf("never-issued")

        assertThat(service.rotate(raw)).isInstanceOf(RotationResult.Rotated::class.java)
    }

    @Test
    fun `Rotated 의 toString 에는 원문 토큰이 찍히지 않는다`() {
        val rotated = service.rotate(service.issue(USER_ID)) as RotationResult.Rotated

        assertThat(rotated.toString()).doesNotContain(rotated.newRaw)
    }

    companion object {
        /** 토큰은 사용자 행을 참조만 하므로(FK 없음) 실제 행이 없어도 된다. */
        private const val USER_ID = 1L
    }
}
