package com.example.credit_system_kotlin.job.concurrency

import com.example.credit_system_kotlin.global.exception.DuplicateRequestInProgressException
import com.example.credit_system_kotlin.job.repository.JobRepository
import com.example.credit_system_kotlin.job.service.HoldService
import com.example.credit_system_kotlin.ledger.repository.LedgerRepository
import com.example.credit_system_kotlin.user.domain.User
import com.example.credit_system_kotlin.user.repository.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

@ActiveProfiles("test")
@SpringBootTest
class DuplicateIdemKeyTest @Autowired constructor(
    private val holdService: HoldService,
    private val jobRepository: JobRepository,
    private val ledgerRepository: LedgerRepository,
    private val userRepository: UserRepository
) {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasourceProps(registry: DynamicPropertyRegistry) {
            SharedContainers.registerDatabase(registry, "duplicate_idem_key")
        }
    }

    @Test
    fun `동일 idemKey로 동시 요청해도 job과 차감은 한 번만 일어난다`() {
        val user = userRepository.save(User("acme", 10_000L))
        val idemKey = "shared-key"

        runConcurrently(10) {
            try {
                holdService.requestGeneration(user.persistedId, idemKey, "cat")
            } catch (e: DuplicateRequestInProgressException) {
                // 선점한 쪽이 아직 jobId를 붙이기 전에 들어온 요청. 정상 경로다.
            }
        }

        assertThat(jobRepository.findByUserIdOrderByIdDesc(user.persistedId)).hasSize(1)
        assertThat(ledgerRepository.findByUserIdOrderByIdDesc(user.persistedId)).hasSize(1)

        val found = userRepository.findById(user.persistedId).orElseThrow()
        assertThat(found.balance).isEqualTo(10_000L - 100L)
    }
}
