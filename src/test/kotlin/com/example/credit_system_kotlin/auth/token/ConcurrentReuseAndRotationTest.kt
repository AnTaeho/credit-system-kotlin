package com.example.credit_system_kotlin.auth.token

import com.example.credit_system_kotlin.job.concurrency.SharedContainers
import com.example.credit_system_kotlin.job.concurrency.runConcurrently
import com.example.credit_system_kotlin.support.FixedMutableClock
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * 재사용 탐지의 사슬 폐기와 같은 사슬의 정상 회전이 겹쳐도 끝난 뒤 살아 있는 토큰이 없는지 실제 MySQL 에서 확인한다.
 * 행 잠금 순서에 달린 일이라 H2 로는 볼 수 없다.
 */
@ActiveProfiles("test")
@SpringBootTest
class ConcurrentReuseAndRotationTest @Autowired constructor(
    private val refreshTokenService: RefreshTokenService,
    private val refreshTokenRepository: RefreshTokenRepository,
    private val clock: FixedMutableClock
) {

    @TestConfiguration
    class MutableClockConfig {
        @Bean
        @Primary
        fun mutableClock(): FixedMutableClock = FixedMutableClock(Instant.parse("2026-01-01T00:00:00Z"))
    }

    companion object {
        private const val ROUNDS = 40

        @JvmStatic
        @DynamicPropertySource
        fun datasourceProps(registry: DynamicPropertyRegistry) {
            SharedContainers.registerDatabase(registry, "concurrent_reuse_and_rotation")
        }
    }

    @Test
    fun `재사용 탐지와 정상 회전이 겹쳐도 그 사슬에 살아 있는 토큰이 남지 않는다`() {
        val survivors = mutableListOf<String>()
        val outcomes = mutableListOf<String>()

        repeat(ROUNDS) { round ->
            val userId = 9_000L + round
            val old = refreshTokenService.issue(userId)
            val current = (refreshTokenService.rotate(old) as RotationResult.Rotated).newRaw
            // 유예(10초)를 넘긴다. 이제 old 가 다시 오면 재사용이다.
            clock.advance(Duration.ofSeconds(11))

            val results = ConcurrentHashMap<Int, String>()
            runConcurrently(2) { idx ->
                results[idx] = try {
                    refreshTokenService.rotate(if (idx == 0) old else current).javaClass.simpleName
                } catch (e: RuntimeException) {
                    "threw ${e.javaClass.simpleName}"
                }
            }

            assertThat(results).`as`("round $round: 두 요청이 모두 끝났다").hasSize(2)
            outcomes += "${results[0]}/${results[1]}"
            val alive = refreshTokenRepository.findAll().filter { it.userId == userId && it.revokedAt == null }
            if (alive.isNotEmpty()) {
                survivors += "round $round (${results[0]}/${results[1]}): 살아 있는 토큰 ${alive.size}장"
            }
        }

        println("재사용 탐지/정상 회전 결과 분포: " + outcomes.groupingBy { it }.eachCount())
        assertThat(survivors).isEmpty()
    }
}
