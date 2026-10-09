package com.example.credit_system_kotlin.integration.job.service

import com.example.credit_system_kotlin.global.exception.InsufficientBalanceException
import com.example.credit_system_kotlin.job.domain.Job
import com.example.credit_system_kotlin.job.domain.JobStatus
import com.example.credit_system_kotlin.job.dto.JobCreateRequest
import com.example.credit_system_kotlin.job.repository.IdempotencyKeyRepository
import com.example.credit_system_kotlin.job.repository.JobRepository
import com.example.credit_system_kotlin.job.service.HoldService
import com.example.credit_system_kotlin.job.service.JobLifecycleService
import com.example.credit_system_kotlin.ledger.repository.LedgerRepository
import com.example.credit_system_kotlin.user.domain.User
import com.example.credit_system_kotlin.user.repository.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import java.time.Instant

@ActiveProfiles("test")
@SpringBootTest
class ServiceTransactionRollbackTest @Autowired constructor(
    private val holdService: HoldService,
    private val jobLifecycleService: JobLifecycleService,
    private val jobRepository: JobRepository,
    private val ledgerRepository: LedgerRepository,
    private val userRepository: UserRepository
) {

    // 멱등 키에 job 을 잇는 UPDATE 가 0행인 상황은 정상 흐름으로 못 만들어 스파이로 바꿔 끼운다.
    @MockitoSpyBean
    private lateinit var idempotencyKeyRepository: IdempotencyKeyRepository

    @AfterEach
    fun tearDown() {
        ledgerRepository.deleteAll()
        idempotencyKeyRepository.deleteAll()
        jobRepository.deleteAll()
        userRepository.deleteAll()
    }

    @Test
    fun `잔액 부족으로 hold가 실패하면 선점한 멱등 키도 롤백된다`() {
        val user = userRepository.save(User("poor", 50L))

        assertThatThrownBy {
            holdService.requestGeneration(user.persistedId, JobCreateRequest("rollback-key", "cat"))
        }
            .isInstanceOf(InsufficientBalanceException::class.java)

        assertThat(
            idempotencyKeyRepository.findByUserIdAndIdemKey(
                user.persistedId, "rollback-key"
            )
        ).isNull()
        assertThat(jobRepository.findByUserIdOrderByIdDesc(user.persistedId)).isEmpty()
        assertThat(ledgerRepository.findByUserIdOrderByIdDesc(user.persistedId)).isEmpty()
        assertThat(userRepository.findById(user.persistedId).orElseThrow().balance)
            .isEqualTo(50L)
    }

    @Test
    fun `멱등 키에 job을 잇지 못하면 차감한 잔액과 만든 job과 멱등 키가 모두 롤백된다`() {
        val user = userRepository.save(User("acme", 1000L))
        doReturn(0).whenever(idempotencyKeyRepository).attachJobId(any(), any(), any())

        assertThatThrownBy {
            holdService.requestGeneration(user.persistedId, JobCreateRequest("attach-key", "cat"))
        }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("idempotency key에 jobId 연결 실패")

        assertThat(userRepository.findById(user.persistedId).orElseThrow().balance)
            .isEqualTo(1000L)
        assertThat(idempotencyKeyRepository.findByUserIdAndIdemKey(user.persistedId, "attach-key")).isNull()
        assertThat(jobRepository.findByUserIdOrderByIdDesc(user.persistedId)).isEmpty()
        assertThat(ledgerRepository.findByUserIdOrderByIdDesc(user.persistedId)).isEmpty()
    }

    @Test
    fun `환불할 사용자가 사라졌으면 REFUNDED 전이도 롤백된다`() {
        val user = userRepository.save(User("deleted", 0L))
        val job = jobRepository.save(Job.hold(user.persistedId, 100L, "cat"))
        jobRepository.startProcessingIfAttemptMatches(job.persistedId, 0, Instant.now())
        jobRepository.failIfProcessing(job.persistedId, 0, Instant.now())
        val failed = jobRepository.findById(job.persistedId).orElseThrow()
        userRepository.deleteById(user.persistedId)

        assertThatThrownBy { jobLifecycleService.finalRefund(failed) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("환불 잔액 반영 실패")

        assertThat(jobRepository.findById(job.persistedId).orElseThrow().status)
            .isEqualTo(JobStatus.FAILED)
        assertThat(ledgerRepository.findByUserIdOrderByIdDesc(user.persistedId)).isEmpty()
    }
}
