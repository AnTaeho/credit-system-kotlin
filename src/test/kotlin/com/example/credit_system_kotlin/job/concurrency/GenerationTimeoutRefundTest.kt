package com.example.credit_system_kotlin.job.concurrency

import com.example.credit_system_kotlin.job.domain.JobStatus
import com.example.credit_system_kotlin.job.repository.JobRepository
import com.example.credit_system_kotlin.job.service.HoldService
import com.example.credit_system_kotlin.ledger.repository.LedgerRepository
import com.example.credit_system_kotlin.user.domain.User
import com.example.credit_system_kotlin.user.repository.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.util.concurrent.TimeUnit

/**
 * 외부가 상한 안에 답하지 못하면 생성 실패와 **같은 결과**로 끝나는지 본다.
 *
 * 스텁은 항상 성공하도록 두고(failure-rate 0) 지연만 상한 위로 올린다. 그래도 job 은
 * 재시도를 모두 소진하고 환불까지 간다 — 타임아웃이 별도 경로로 새지 않는다는 뜻이다.
 */
@ActiveProfiles("test")
@SpringBootTest(
    properties = [
        "app.scheduling.enabled=true",
        "app.worker.enabled=true",
        "app.stub.failure-rate=0.0",
        "app.stub.min-delay-millis=3000",
        "app.stub.max-delay-millis=3000",
        "app.generation.timeout-seconds=1",
        "app.scheduling.worker-interval-millis=100",
        "app.scheduling.dead-job-scan-interval-millis=500"
    ]
)
class GenerationTimeoutRefundTest @Autowired constructor(
    private val holdService: HoldService,
    private val jobRepository: JobRepository,
    private val userRepository: UserRepository,
    private val ledgerRepository: LedgerRepository
) {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            SharedContainers.registerDatabase(registry, "generation_timeout_refund")
        }
    }

    @Test
    fun `매번 타임아웃이면 재시도를 모두 소진하고 최종적으로 환불된다`() {
        val user = userRepository.save(User("acme", 1000L))

        val result = holdService.requestGeneration(user.persistedId, "timeout-key", "cat")

        await().atMost(30, TimeUnit.SECONDS).untilAsserted {
            val job = jobRepository.findById(result.jobId).orElseThrow()
            assertThat(job.status).isEqualTo(JobStatus.REFUNDED)
            assertThat(job.attemptNo).isEqualTo(2)
        }

        val found = userRepository.findById(user.persistedId).orElseThrow()
        assertThat(found.balance).isEqualTo(1000L)
        assertThat(ledgerRepository.findByUserIdOrderByIdDesc(user.persistedId))
            .extracting<String> { it.type.name }
            .contains("HOLD", "REFUND")
    }
}
