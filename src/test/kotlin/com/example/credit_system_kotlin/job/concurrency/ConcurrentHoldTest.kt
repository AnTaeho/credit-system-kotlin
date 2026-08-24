package com.example.credit_system_kotlin.job.concurrency

import com.example.credit_system_kotlin.global.InsufficientBalanceException
import com.example.credit_system_kotlin.job.service.HoldService
import com.example.credit_system_kotlin.organization.Organization
import com.example.credit_system_kotlin.organization.OrganizationRepository
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
    private val organizationRepository: OrganizationRepository
) {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasourceProps(registry: DynamicPropertyRegistry) {
            SharedContainers.registerDatabase(registry, "concurrent_hold")
        }
    }

    @Test
    fun `동시에 여러 요청이 들어와도 잔액이 음수가 되지 않는다`() {
        val organization = organizationRepository.save(Organization("acme", 500L))

        val threadCount = 10
        val successCount = AtomicInteger()
        val rejectedCount = AtomicInteger()

        runConcurrently(threadCount) { idx ->
            try {
                holdService.requestGeneration(organization.persistedId, "concurrent-key-$idx", "cat")
                successCount.incrementAndGet()
            } catch (e: InsufficientBalanceException) {
                rejectedCount.incrementAndGet()
            }
        }

        assertThat(successCount.get() + rejectedCount.get()).isEqualTo(threadCount)
        assertThat(successCount.get()).isEqualTo(5)
        assertThat(rejectedCount.get()).isEqualTo(5)

        val found = organizationRepository.findById(organization.persistedId).orElseThrow()
        assertThat(found.balance).isEqualTo(0L)
    }
}
