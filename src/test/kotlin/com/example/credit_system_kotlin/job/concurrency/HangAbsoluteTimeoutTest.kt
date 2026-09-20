package com.example.credit_system_kotlin.job.concurrency

import com.example.credit_system_kotlin.job.domain.JobStatus
import com.example.credit_system_kotlin.job.generation.stub.GenerationStubClient
import com.example.credit_system_kotlin.job.repository.JobRepository
import com.example.credit_system_kotlin.job.service.HoldService
import com.example.credit_system_kotlin.ledger.repository.LedgerRepository
import com.example.credit_system_kotlin.observability.DefenseMetrics
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
 * **멈춘 워커의 돈을 절대 상한이 푸는지** 본다. step11-B 의 존재 이유 그 자체다.
 *
 * 스텁을 hang 모드로 두면 워커 스레드가 외부 호출에서 영영 돌아오지 않는다. 그런데 종지기
 * 스레드는 워커 상태를 보지 않고 5초마다 heartbeat 를 갱신하므로 heartbeat 는 영원히 LIVE 다.
 * 그래서 기존 두 그물(heartbeat 만료, updatedAt 정체 + heartbeat 부재) 모두 이 job 을 놓친다.
 * `app.processing.absolute-timeout-seconds` 만이 이 돈을 푼다.
 *
 * **한 메서드에 두 국면을 담은 이유.** [GenerationStubClient.releaseHang] 의 래치는 컨텍스트당
 * 1회용이고 Spring 은 클래스 안의 테스트 메서드들이 같은 컨텍스트를 쓰게 한다. 두 번째 메서드로
 * 나누면 좀비를 깨울 래치가 이미 내려가 있어 hang 자체가 재현되지 않는다. 컨테이너를 쓰는
 * 컨텍스트를 하나 더 띄우는 대신 국면을 나눠 적었다.
 *
 * **concurrency 를 4 로 둔 이유.** 절대 상한은 job 을 PROCESSING 에서 풀어 주지만, 그 뒤의
 * 재시도는 빈 워커 슬롯을 필요로 한다. 멈춘 시도 수가 concurrency 와 같아지면 디스패처가
 * `free()=0` 을 보고 아무것도 선점하지 못해, job 은 HOLDING 에서 늙고 환불까지 가지 못한다.
 * 즉 **"돈을 푼다"는 멈춘 시도 수 < concurrency 일 때만 끝까지 간다.** 시도 3번이 전부
 * 멈추는 이 시나리오를 끝까지 보려면 자리가 하나 더 있어야 한다. 이것이 슬롯 누수의 대가다.
 *
 * `finally` 의 `releaseHang()` 은 장식이 아니다. 공유 Redis ZSET 에 남은 고아 heartbeat 멤버는
 * 계속 갱신되는 한 만료되지 않아 다른 테스트 컨텍스트가 물려받는다. 스레드가 끝나야 사라진다.
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
        "app.scheduling.dead-job-scan-interval-millis=500"
    ]
)
class HangAbsoluteTimeoutTest @Autowired constructor(
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
            SharedContainers.registerDatabase(registry, "hang_absolute_timeout")
        }

        private const val MAX_ATTEMPTS = 3
        private const val START_BALANCE = 1000L
    }

    private fun recoveryCount(detector: String): Double =
        registry.get(DefenseMetrics.RECOVERY_METRIC).tag("detector", detector).counter().count()

    private fun defenseCount(point: String, outcome: String): Double =
        registry.get(DefenseMetrics.DEFENSE_METRIC)
            .tag("point", point).tag("outcome", outcome).counter().count()

    @Test
    fun `멈춘 워커의 돈은 절대 상한이 풀고 뒤늦게 돌아온 좀비는 아무것도 못 움직인다`() {
        val user = userRepository.save(User("acme", START_BALANCE))
        val jobId = holdService.requestGeneration(user.persistedId, "hang-key", "cat").jobId

        try {
            // 국면 1 — heartbeat 는 LIVE 인데도 절대 상한이 회수한다. 재시도를 모두 소진하고 환불까지 간다.
            await().atMost(60, TimeUnit.SECONDS).untilAsserted {
                val job = jobRepository.findById(jobId).orElseThrow()
                assertThat(job.status).isEqualTo(JobStatus.REFUNDED)
                assertThat(job.attemptNo).isEqualTo(MAX_ATTEMPTS - 1)
            }

            assertThat(userRepository.findById(user.persistedId).orElseThrow().balance)
                .describedAs("묶였던 돈이 전부 돌아와야 한다")
                .isEqualTo(START_BALANCE)
            assertThat(ledgerRepository.findByUserIdOrderByIdDesc(user.persistedId))
                .extracting<String> { it.type.name }
                .contains("HOLD", "REFUND")

            assertThat(recoveryCount("hard_cap"))
                .describedAs("시도 3번 전부 hard_cap 이 잡아야 한다")
                .isEqualTo(MAX_ATTEMPTS.toDouble())
            assertThat(recoveryCount("backstop"))
                .describedAs("heartbeat 는 내내 LIVE 였으므로 backstop 은 한 번도 잡지 못한다")
                .isZero()

            // 절대 상한은 돈만 푼다. 스레드 3개는 여전히 hang 에 묶여 있다.
            assertThat(registry.get(WorkerSlotMetrics.FREE_SLOTS_METRIC).gauge().value())
                .describedAs("묶인 시도 3개만큼 슬롯이 줄어 있어야 한다 — 이것이 슬롯 누수의 모습이다")
                .isEqualTo(1.0)
        } finally {
            stubClient.releaseHang()
        }

        // 국면 2 — 좀비가 뒤늦게 돌아온다. attemptNo 가 어긋나 전이가 0행으로 막힌다.
        // 스텁은 풀려나도 성공하지 않고 타임아웃으로 끝나므로(hangForever 주석) 좀비의 경로는
        // markFailed 이고, 그 0행은 mark_failed/stale 로 센다.
        await().atMost(30, TimeUnit.SECONDS).untilAsserted {
            assertThat(defenseCount("mark_failed", "stale")).isEqualTo(MAX_ATTEMPTS.toDouble())
        }

        val job = jobRepository.findById(jobId).orElseThrow()
        assertThat(job.status).describedAs("좀비가 종결 상태를 되돌리지 못한다").isEqualTo(JobStatus.REFUNDED)
        assertThat(userRepository.findById(user.persistedId).orElseThrow().balance)
            .describedAs("돈이 두 번 움직이지 않는다")
            .isEqualTo(START_BALANCE)
        assertThat(ledgerRepository.findByUserIdOrderByIdDesc(user.persistedId))
            .describedAs("HOLD 1 + REFUND 1 뿐이다")
            .hasSize(2)

        await().atMost(30, TimeUnit.SECONDS).untilAsserted {
            assertThat(registry.get(WorkerSlotMetrics.FREE_SLOTS_METRIC).gauge().value())
                .describedAs("스레드가 끝나야 슬롯이 돌아온다")
                .isEqualTo(4.0)
        }
    }
}
