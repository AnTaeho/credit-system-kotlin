package com.example.credit_system_kotlin.invariants

import com.example.credit_system_kotlin.job.concurrency.SharedContainers
import com.example.credit_system_kotlin.job.domain.JobStatus
import com.example.credit_system_kotlin.job.generation.stub.GenerationStubClient
import com.example.credit_system_kotlin.job.repository.JobRepository
import com.example.credit_system_kotlin.job.service.HoldService
import com.example.credit_system_kotlin.ledger.repository.LedgerRepository
import com.example.credit_system_kotlin.observability.WorkerSlotMetrics
import com.example.credit_system_kotlin.user.domain.User
import com.example.credit_system_kotlin.user.repository.UserRepository
import io.micrometer.core.instrument.MeterRegistry
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
 * **INV-05 (1/2) — 미결로 묶인 돈은 상한 T 안에 풀린다.** 요구서 4-4 의 산술을 **실제로 잰다.**
 *
 * 요구서는 운영 설정에서 최악 경로(hang)가 950~966초 ≈ 16.1분이라고 계산했고, 그래서
 * T 를 17분으로 올렸다. 그 산술을 그대로 돌려 볼 수는 없다 — 한 번에 16분이 걸린다.
 * 그래서 **같은 구조의 축소 설정**으로 돌리고, 걸린 시각이 **그 축소 설정의 산술 상한** 안에
 * 드는지 본다. 검증하는 것은 초 단위 숫자가 아니라 **식이 맞다는 것**이다.
 *
 * ```
 * 상한 = max-attempts(3) × absolute-timeout-seconds(3)
 *      + backoff(1 + 1)                      ← test 프로파일: base 1초, multiplier 1
 *      + max-attempts(3) × dead-job-scan(0.5)
 *      + 3 × worker-interval(0.1)            ← 시도 0 의 최초 디스패치까지 포함해 3회다
 *      = 9 + 2 + 1.5 + 0.3 = 12.8초
 * ```
 *
 * **[SLACK_MILLIS] 를 따로 두는 이유.** 위 식은 각 단계가 자기 차례에 곧바로 실행된다고 가정한다.
 * 실제로는 스케줄러 풀(`spring.task.scheduling.pool.size: 4`)을 디스패처·회수·대사·스냅샷이
 * 나눠 쓰고, Testcontainers MySQL 과 Redis 왕복이 끼며, 컨텍스트가 막 뜬 직후에는 JIT 이
 * 덥혀지지 않았다. 그 지터를 식에 섞으면 식이 무엇을 말하는지가 흐려지므로, 산술 상한과
 * 계측 여유를 **따로** 적고 둘의 합에 대고 단언한다. 실제 값은 표준 출력에 찍는다.
 *
 * 다른 절반 — **상한이 아예 없는 경로** — 는 [HangSlotStarvationTest] 가 고정한다.
 *
 * `concurrency=4` 인 이유와 `finally` 의 `releaseHang()` 이 필요한 이유는
 * [com.example.credit_system_kotlin.job.concurrency.HangAbsoluteTimeoutTest] KDoc 과 같다.
 */
@ActiveProfiles("test")
@SpringBootTest(
    properties = [
        "app.scheduling.enabled=true",
        "app.worker.enabled=true",
        "app.worker.concurrency=4",
        "app.stub.hang=true",
        "app.generation.timeout-seconds=1",
        "app.processing.timeout-seconds=1",
        "app.processing.absolute-timeout-seconds=3",
        "app.scheduling.worker-interval-millis=100",
        "app.scheduling.dead-job-scan-interval-millis=500",
        // 살아 있는 컨텍스트마다 풀을 쥐고 있으므로 공유 컨테이너의 커넥션을 아껴 쓴다.
        "spring.datasource.hikari.maximum-pool-size=5"
    ]
)
class OutstandingMoneyBoundTest @Autowired constructor(
    private val holdService: HoldService,
    private val jobRepository: JobRepository,
    private val userRepository: UserRepository,
    private val ledgerRepository: LedgerRepository,
    private val stubClient: GenerationStubClient,
    private val registry: MeterRegistry
) {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            SharedContainers.registerDatabase(registry, "inv05_bound")
        }

        private const val START_BALANCE = 1000L
        private const val MAX_ATTEMPTS = 3

        /** 위 KDoc 의 식 그대로. 설정을 바꾸면 이 숫자도 함께 고쳐야 한다. */
        private const val ARITHMETIC_BOUND_MILLIS = 12_800L

        /** 산술이 가정하지 않는 실행 지터(스케줄러 풀 경합, 컨테이너 왕복, 워밍업)의 몫. */
        private const val SLACK_MILLIS = 10_000L
    }

    @Test
    fun `hang 으로 묶인 돈이 축소 설정의 산술 상한 안에 풀린다`() {
        val user = userRepository.save(User("inv05", START_BALANCE))

        val startedAt = System.currentTimeMillis()
        val jobId = holdService.requestGeneration(user.persistedId, "inv05-key", "cat").jobId

        try {
            await().atMost(60, TimeUnit.SECONDS).untilAsserted {
                val job = jobRepository.findById(jobId).orElseThrow()
                assertThat(job.status).isEqualTo(JobStatus.REFUNDED)
                assertThat(job.attemptNo).isEqualTo(MAX_ATTEMPTS - 1)
            }
            val elapsed = System.currentTimeMillis() - startedAt

            println(
                "INV-05 축소 설정 실측: hold → REFUNDED 까지 ${elapsed}ms " +
                    "(산술 상한 ${ARITHMETIC_BOUND_MILLIS}ms + 계측 여유 ${SLACK_MILLIS}ms)"
            )
            assertThat(elapsed)
                .describedAs("묶인 돈이 풀리기까지 걸린 시간")
                .isLessThanOrEqualTo(ARITHMETIC_BOUND_MILLIS + SLACK_MILLIS)

            assertThat(userRepository.findById(user.persistedId).orElseThrow().balance)
                .describedAs("풀렸다는 것은 잔액이 원상이라는 뜻이다")
                .isEqualTo(START_BALANCE)
            assertThat(ledgerRepository.findByUserIdOrderByIdDesc(user.persistedId))
                .describedAs("HOLD 1 + REFUND 1")
                .hasSize(2)
        } finally {
            stubClient.releaseHang()
        }

        // 멈춘 스레드 3개를 남긴 채 끝나면 공유 Redis 의 고아 heartbeat 가 다음 컨텍스트로 넘어간다.
        await().atMost(30, TimeUnit.SECONDS).untilAsserted {
            assertThat(registry.get(WorkerSlotMetrics.FREE_SLOTS_METRIC).gauge().value()).isEqualTo(4.0)
        }
    }
}
