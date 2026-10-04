package com.example.credit_system_kotlin.auth.token

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
class RefreshTokenCleanupTaskTest @Autowired constructor(
    private val refreshTokenRepository: RefreshTokenRepository
) {

    private val clock = FixedMutableClock(Instant.parse("2026-01-10T00:00:00Z"))

    private val task = RefreshTokenCleanupTask(refreshTokenRepository, clock)

    private fun saveExpiredFor(sinceExpiry: Duration, tokenHash: String): RefreshToken =
        refreshTokenRepository.save(
            RefreshToken(1L, tokenHash, "family-$tokenHash", clock.instant().minus(sinceExpiry))
        )

    @Test
    fun `만료된 지 하루가 넘은 토큰은 삭제된다`() {
        val token = saveExpiredFor(Duration.ofDays(1).plusSeconds(1), "old")

        task.cleanup()

        assertThat(refreshTokenRepository.findById(token.persistedId)).isEmpty()
    }

    @Test
    fun `만료된 지 하루가 안 된 토큰과 아직 유효한 토큰은 남는다`() {
        val justExpired = saveExpiredFor(Duration.ofHours(23), "just-expired")
        val alive = saveExpiredFor(Duration.ofDays(-13), "alive")

        task.cleanup()

        assertThat(refreshTokenRepository.findById(justExpired.persistedId)).isPresent()
        assertThat(refreshTokenRepository.findById(alive.persistedId)).isPresent()
    }

    @Test
    fun `배치 크기를 넘는 토큰도 모두 삭제된다`() {
        repeat(510) { saveExpiredFor(Duration.ofDays(2), "hash-$it") }

        task.cleanup()

        assertThat(refreshTokenRepository.count()).isZero()
    }

    @Test
    fun `지울 토큰이 없으면 아무것도 지우지 않는다`() {
        task.cleanup()

        assertThat(refreshTokenRepository.count()).isZero()
    }
}
