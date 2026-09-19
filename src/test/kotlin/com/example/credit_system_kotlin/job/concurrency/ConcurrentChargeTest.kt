package com.example.credit_system_kotlin.job.concurrency

import com.example.credit_system_kotlin.ledger.domain.LedgerType
import com.example.credit_system_kotlin.ledger.repository.LedgerRepository
import com.example.credit_system_kotlin.user.domain.User
import com.example.credit_system_kotlin.user.repository.UserRepository
import com.example.credit_system_kotlin.user.service.UserService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

@ActiveProfiles("test")
@SpringBootTest
class ConcurrentChargeTest @Autowired constructor(
    private val userService: UserService,
    private val ledgerRepository: LedgerRepository,
    private val userRepository: UserRepository
) {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasourceProps(registry: DynamicPropertyRegistry) {
            SharedContainers.registerDatabase(registry, "concurrent_charge")
        }
    }

    @Test
    fun `동일 idemKey로 동시 충전해도 잔액은 한 번만 오른다`() {
        val user = userRepository.save(User("acme", 10_000L))
        val idemKey = "shared-charge-key"

        runConcurrently(10) {
            try {
                userService.charge(user.persistedId, idemKey, 300L)
            } catch (e: DataIntegrityViolationException) {
                // 유니크 제약에서 밀린 쪽. 잔액이 오르지 않는 것이 정상이므로 무시한다.
            }
        }

        assertThat(ledgerRepository.findByUserIdOrderByIdDesc(user.persistedId))
            .filteredOn { it.type == LedgerType.CHARGE }
            .hasSize(1)

        val found = userRepository.findById(user.persistedId).orElseThrow()
        assertThat(found.balance).isEqualTo(10_000L + 300L)
    }
}
