package com.example.credit_system_kotlin.auth.token

import com.example.credit_system_kotlin.job.concurrency.SharedContainers
import com.example.credit_system_kotlin.job.concurrency.runConcurrently
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 같은 리프레시 토큰으로 동시에 갱신해도 새 토큰은 하나만 나오는지 실제 MySQL 에서 확인한다.
 * 진 쪽이 이긴 쪽의 `rotated_at` 을 보는지는 REPEATABLE READ 의 스냅샷 시점에 달려 있어 H2 로는 드러나지 않는다.
 */
@ActiveProfiles("test")
@SpringBootTest
class ConcurrentRefreshRotationTest @Autowired constructor(
    private val refreshTokenService: RefreshTokenService,
    private val refreshTokenRepository: RefreshTokenRepository
) {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasourceProps(registry: DynamicPropertyRegistry) {
            SharedContainers.registerDatabase(registry, "concurrent_refresh_rotation")
        }
    }

    @Test
    fun `같은 리프레시로 동시에 회전하면 하나만 Rotated 이고 나머지는 WithinGrace 다`() {
        val userId = 77L
        val raw = refreshTokenService.issue(userId)
        val threadCount = 10
        val results = ConcurrentLinkedQueue<RotationResult>()

        runConcurrently(threadCount) { results.add(refreshTokenService.rotate(raw)) }

        assertThat(results).hasSize(threadCount)
        assertThat(results.filterIsInstance<RotationResult.Rotated>()).hasSize(1)
        assertThat(results.filterIsInstance<RotationResult.WithinGrace>())
            .hasSize(threadCount - 1)
            .allSatisfy { assertThat(it.userId).isEqualTo(userId) }

        // 토큰도 처음 것과 회전으로 나온 것, 둘뿐이고 같은 사슬이며 폐기되지 않았다.
        val tokens = refreshTokenRepository.findAll().filter { it.userId == userId }
        assertThat(tokens).hasSize(2)
        assertThat(tokens.map { it.familyId }.distinct()).hasSize(1)
        assertThat(tokens).allSatisfy { assertThat(it.revokedAt).isNull() }
    }
}
