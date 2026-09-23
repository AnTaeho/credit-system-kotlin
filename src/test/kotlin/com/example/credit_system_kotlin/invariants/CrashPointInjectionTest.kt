package com.example.credit_system_kotlin.invariants

import com.example.credit_system_kotlin.job.concurrency.SharedContainers
import com.example.credit_system_kotlin.job.domain.Job
import com.example.credit_system_kotlin.job.domain.JobStatus
import com.example.credit_system_kotlin.job.generation.GenerationClient
import com.example.credit_system_kotlin.job.generation.GenerationTimeoutException
import com.example.credit_system_kotlin.job.repository.JobRepository
import com.example.credit_system_kotlin.job.service.HoldService
import com.example.credit_system_kotlin.job.service.JobLifecycleService
import com.example.credit_system_kotlin.ledger.domain.LedgerType
import com.example.credit_system_kotlin.ledger.repository.LedgerRepository
import com.example.credit_system_kotlin.user.domain.User
import com.example.credit_system_kotlin.user.repository.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * **INV-04a — 외부 호출이 성공했든 실패했든, 사용자에게서 나가는 돈은 정확히 1회다**
 * (요구서 4절 INV 표, 4-3). 인벤토리 3-2 의 **C3c·C4** 지점을 재현한다.
 *
 * **한계를 먼저 적는다.** 이것은 **트랜잭션·스레드 수준의 재현이고 프로세스 사망이 아니다.**
 * in-JVM 테스트로는 `kill -9` 를 흉내 낼 수 없다 —
 * [com.example.credit_system_kotlin.job.concurrency.GenerationDrainOnShutdownTest] 조차
 * `SpringApplication` 을 띄웠다 정상 종료시키는 것이지 프로세스를 죽이는 것이 아니다.
 * 실제 kill -9 증거는 `deploy/observability/scenarios/01-worker-crash.sh` 다.
 *
 * 그래도 이 재현이 값어치가 있는 이유: C4·C3c 가 시스템에 남기는 **상태**는 "PROCESSING 인데
 * 아무도 그 시도를 끝내지 않는다"이고, 그 상태는 프로세스를 죽이지 않고도 정확히 만들 수 있다.
 * 회수 경로(`DeadJobRecoveryTask`)가 보는 것은 죽은 프로세스가 아니라 그 상태뿐이다.
 *
 * **주입은 테스트 더블로만 한다.** 프로덕션에 fault 플래그를 심지 않는다.
 * - C4: [GenerationClient] 는 성공을 돌려주는데 [JobLifecycleService.confirm] 이 던진다.
 * - C3c: [GenerationClient] 가 상한까지 기다리다 [GenerationTimeoutException] 을 던진다.
 *   (프로퍼티로만 재현한 단건 판이
 *   [com.example.credit_system_kotlin.job.concurrency.GenerationTimeoutRefundTest] 다. 여기서는
 *   원장이 **정확히 HOLD 1 + REFUND 1** 이라는 것까지 못박는다.)
 *
 * **`absolute-timeout-seconds` 까지 줄여 둔 이유.** 이 컨텍스트의 정상 회수 경로는
 * heartbeat 부재(BACKSTOP)다 — 워커 스레드가 `finally` 에서 heartbeat 를 지우고 끝나기
 * 때문이다. 다만 Redis 는 JVM 전체가 공유하고 DB 는 컨텍스트마다 새로 만들어 job id 가 1부터
 * 다시 시작하므로, 다른 컨텍스트가 남긴 같은 `jobId:attemptNo` 멤버 때문에 heartbeat 가
 * LIVE 로 읽힐 수 있다. 그때도 절대 상한이 몇 초 안에 풀도록 함께 줄였다.
 */
@ActiveProfiles("test")
@SpringBootTest(
    properties = [
        "app.scheduling.enabled=true",
        "app.worker.enabled=true",
        "app.processing.timeout-seconds=1",
        "app.processing.absolute-timeout-seconds=3",
        "app.scheduling.worker-interval-millis=100",
        "app.scheduling.dead-job-scan-interval-millis=500",
        // 살아 있는 컨텍스트마다 풀을 쥐고 있으므로 공유 컨테이너의 커넥션을 아껴 쓴다.
        "spring.datasource.hikari.maximum-pool-size=5"
    ]
)
class CrashPointInjectionTest @Autowired constructor(
    private val holdService: HoldService,
    private val jobRepository: JobRepository,
    private val userRepository: UserRepository,
    private val ledgerRepository: LedgerRepository
) {

    /** 스텁 대신 꽂는 외부 클라이언트. 메서드마다 무엇을 낼지 다시 정한다. */
    @MockitoBean
    private lateinit var generationClient: GenerationClient

    /** 실제 빈을 감싸는 spy. C4 에서만 `confirm` 을 던지게 하고 나머지는 진짜를 그대로 쓴다. */
    @MockitoSpyBean
    private lateinit var jobLifecycleService: JobLifecycleService

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            SharedContainers.registerDatabase(registry, "inv04a_crash")
        }

        private const val START_BALANCE = 1000L
        private const val MAX_ATTEMPTS = 3
    }

    private fun awaitRefunded(jobId: Long) {
        await().atMost(60, TimeUnit.SECONDS).untilAsserted {
            val job = jobRepository.findById(jobId).orElseThrow()
            assertThat(job.status).isEqualTo(JobStatus.REFUNDED)
            assertThat(job.attemptNo).isEqualTo(MAX_ATTEMPTS - 1)
        }
    }

    private fun assertMoneyLeftExactlyOnceAndCameBack(userId: Long) {
        assertThat(userRepository.findById(userId).orElseThrow().balance)
            .describedAs("사용자 잔액은 원상이어야 한다 — 나간 돈은 정확히 1회이고 그 1회가 되돌아왔다")
            .isEqualTo(START_BALANCE)

        val entries = ledgerRepository.findByUserIdOrderByIdDesc(userId)
        assertThat(entries).describedAs("원장은 HOLD 1 + REFUND 1, 두 줄뿐이다").hasSize(2)
        assertThat(entries.count { it.type == LedgerType.HOLD })
            .describedAs("HOLD 는 한 번뿐 — 재시도가 3번이어도 차감은 최초 1회다")
            .isEqualTo(1)
        assertThat(entries.count { it.type == LedgerType.REFUND }).isEqualTo(1)
        assertThat(entries.sumOf { it.amount })
            .describedAs("원장 합이 0 이어야 잔액이 원상이다 (HOLD 는 음수, REFUND 는 양수)")
            .isZero()
    }

    /**
     * **C4 — 외부 호출 성공 직후, `confirm` 전에 죽는다.**
     *
     * 외부는 매번 성공하지만 `confirm` 이 매번 던진다. job 은 PROCESSING 에 남고
     * (`GenerationJobProcessor.confirm` 이 예외를 삼키고 회수에 맡긴다), 회수·재시도를 거쳐
     * 상한 3회를 소진한 뒤 `finalRefund` 로 끝난다. **결과 URL 은 유실되고 외부 원가는
     * 3번 나갔지만**(그것이 INV-04b 의 관심사다) 사용자 돈은 원상이다.
     */
    @Test
    fun `C4 - 외부는 성공했는데 confirm 이 죽으면 회수 경로가 돈을 원상으로 돌린다`() {
        whenever(generationClient.generate(any()))
            .doAnswer { "https://stub-images.local/${UUID.randomUUID()}.png" }
        doThrow(IllegalStateException("C4 주입: confirm 직전에 죽는다"))
            .whenever(jobLifecycleService).confirm(any<Job>(), any())

        val user = userRepository.save(User("inv04a-c4", START_BALANCE))
        val jobId = holdService.requestGeneration(user.persistedId, "inv04a-c4-key", "cat").jobId

        awaitRefunded(jobId)
        assertMoneyLeftExactlyOnceAndCameBack(user.persistedId)
        assertThat(jobRepository.findById(jobId).orElseThrow().resultUrl)
            .describedAs("C4 의 대가 — 이미 만들어진 결과 URL 은 DB 에 남지 않는다 (요구서 4-3 '서비스 손실')")
            .isNull()
    }

    /**
     * **C3c — 외부 호출이 전송된 뒤 응답 전에 죽는다.**
     *
     * 시스템이 볼 수 있는 것은 "상한까지 기다렸는데 답이 없었다"뿐이다. 부작용이 일어났는지는
     * 알 수 없고(요구서 4-3), 그래서 이 지점은 **INV-04a 가 아니라 INV-04b 로 닫는다.**
     * INV-04a 가 요구하는 것 — 사용자 돈이 원상인가 — 은 여기서 그대로 성립한다.
     */
    @Test
    fun `C3c - 외부가 상한까지 답하지 않아도 사용자 돈은 원상으로 돌아온다`() {
        whenever(generationClient.generate(any())).doAnswer {
            Thread.sleep(200)
            throw GenerationTimeoutException("cat", 200)
        }

        val user = userRepository.save(User("inv04a-c3c", START_BALANCE))
        val jobId = holdService.requestGeneration(user.persistedId, "inv04a-c3c-key", "cat").jobId

        awaitRefunded(jobId)
        assertMoneyLeftExactlyOnceAndCameBack(user.persistedId)
    }
}
