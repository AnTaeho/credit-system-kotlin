package com.example.credit_system_kotlin.job.concurrency

import com.example.credit_system_kotlin.global.exception.InsufficientBalanceException
import com.example.credit_system_kotlin.job.service.HoldService
import com.example.credit_system_kotlin.support.DefenseCounters
import com.example.credit_system_kotlin.user.domain.User
import com.example.credit_system_kotlin.user.repository.UserRepository
import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.util.concurrent.atomic.AtomicInteger

@ActiveProfiles("test")
@SpringBootTest
class ConcurrentHoldTest @Autowired constructor(
    private val holdService: HoldService,
    private val userRepository: UserRepository,
    meterRegistry: MeterRegistry
) {

    private val defenseCounters = DefenseCounters(meterRegistry)

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasourceProps(registry: DynamicPropertyRegistry) {
            SharedContainers.registerDatabase(registry, "concurrent_hold")
        }
    }

    @Test
    fun `동시에 여러 요청이 들어와도 잔액이 음수가 되지 않는다`() {
        val user = userRepository.save(User("acme", 500L))

        val threadCount = 10
        val successCount = AtomicInteger()
        val rejectedCount = AtomicInteger()
        val before = defenseCounters.snapshot("hold_balance" to "applied", "hold_balance" to "rejected")

        runConcurrently(threadCount) { idx ->
            try {
                holdService.requestGeneration(user.persistedId, "concurrent-key-$idx", "cat")
                successCount.incrementAndGet()
            } catch (e: InsufficientBalanceException) {
                rejectedCount.incrementAndGet()
            }
        }

        assertThat(successCount.get() + rejectedCount.get()).isEqualTo(threadCount)
        assertThat(successCount.get()).isEqualTo(5)
        assertThat(rejectedCount.get()).isEqualTo(5)

        val found = userRepository.findById(user.persistedId).orElseThrow()
        assertThat(found.balance).isEqualTo(0L)

        // 카운터와 실제 돈이 같은 이야기를 해야 한다.
        assertThat(defenseCounters.delta(before, "hold_balance", "applied"))
            .isEqualTo(successCount.get().toDouble())
        assertThat(defenseCounters.delta(before, "hold_balance", "rejected"))
            .isEqualTo(rejectedCount.get().toDouble())
    }
}
