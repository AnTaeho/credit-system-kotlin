package com.example.credit_system_kotlin.job.concurrency

import com.example.credit_system_kotlin.global.exception.DuplicateRequestInProgressException
import com.example.credit_system_kotlin.global.exception.GlobalExceptionHandler
import com.example.credit_system_kotlin.job.dto.JobCreateRequest
import com.example.credit_system_kotlin.job.repository.JobRepository
import com.example.credit_system_kotlin.job.service.HoldService
import com.example.credit_system_kotlin.ledger.repository.LedgerRepository
import com.example.credit_system_kotlin.support.DefenseCounters
import com.example.credit_system_kotlin.user.domain.User
import com.example.credit_system_kotlin.user.repository.UserRepository
import io.micrometer.core.instrument.MeterRegistry
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
class DuplicateIdemKeyTest @Autowired constructor(
    private val holdService: HoldService,
    private val jobRepository: JobRepository,
    private val ledgerRepository: LedgerRepository,
    private val userRepository: UserRepository,
    private val globalExceptionHandler: GlobalExceptionHandler,
    meterRegistry: MeterRegistry
) {

    private val defenseCounters = DefenseCounters(meterRegistry)

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasourceProps(registry: DynamicPropertyRegistry) {
            SharedContainers.registerDatabase(registry, "duplicate_idem_key")
        }
    }

    /** `db_unique` 는 서비스 밖 `GlobalExceptionHandler` 에서 발행된다. 서비스에서 나온 예외를 그 핸들러에 그대로 넘긴다. */
    @Test
    fun `동일 idemKey로 동시 요청해도 job과 차감은 한 번만 일어난다`() {
        val user = userRepository.save(User("acme", 10_000L))
        val idemKey = "shared-key"
        val before = defenseCounters.snapshot(
            "hold_balance" to "applied",
            "idem_key" to "app_hit",
            "idem_key" to "db_unique"
        )

        runConcurrently(10) {
            try {
                holdService.requestGeneration(user.persistedId, JobCreateRequest(idemKey, "cat"))
            } catch (e: DuplicateRequestInProgressException) {
                // 선점한 쪽이 아직 jobId를 붙이기 전에 들어온 요청. 정상 경로다.
            } catch (e: DataIntegrityViolationException) {
                globalExceptionHandler.handleDataIntegrityViolation(e)
            }
        }

        assertThat(jobRepository.findByUserIdOrderByIdDesc(user.persistedId)).hasSize(1)
        assertThat(ledgerRepository.findByUserIdOrderByIdDesc(user.persistedId)).hasSize(1)

        val found = userRepository.findById(user.persistedId).orElseThrow()
        assertThat(found.balance).isEqualTo(10_000L - 100L)

        val appHit = defenseCounters.delta(before, "idem_key", "app_hit")
        val dbUnique = defenseCounters.delta(before, "idem_key", "db_unique")
        assertThat(appHit + dbUnique).isEqualTo(9.0)
        assertThat(defenseCounters.delta(before, "hold_balance", "applied")).isEqualTo(1.0)
    }
}
