package com.example.credit_system_kotlin.invariants

import com.example.credit_system_kotlin.job.concurrency.SharedContainers
import com.example.credit_system_kotlin.job.domain.JobStatus
import com.example.credit_system_kotlin.job.generation.stub.GenerationStubClient
import com.example.credit_system_kotlin.job.repository.JobRepository
import com.example.credit_system_kotlin.job.service.HoldService
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
 * **INV-05 (2/2) — 상한이 없는 경로를 고정한다. 이 테스트는 "깨진 것"을 그대로 단언한다.**
 *
 * 요구서 4-4 가 기록한 gap 이다(2026-09-23): hang 은 워커 슬롯을 돌려주지 않으므로
 * `workerSlots.free()` 가 0 이 되면 디스패처는 조회조차 하지 않고(인벤토리 3-1 S2),
 * **뒤에 줄 선 job 은 HOLDING 에서 늙기만 한다. 그 job 의 T 에는 상한이 없다.**
 * 그래서 INV-05 의 현재 상태는 "실패"이고, 이유는 "최악이 T 를 넘는다"가 아니라
 * "상한이 없는 경로가 있다"이다.
 *
 * **이것은 고쳐야 할 버그를 재현한 것이 아니라 gap 을 고정한 것이다.** 닫으려면 멈춘 워커
 * 스레드를 회수(인터럽트)해야 하고 그것은 서비스 코드 변경이라 이 조각(테스트·계측만)의
 * 밖이다. 고치는 날 이 테스트는 **깨질 것이고, 깨져야 한다** — 그때 단언을 뒤집는 것이
 * 바로 "gap 을 닫았다"의 증거다.
 *
 * `concurrency=1` 로 줄여 슬롯 고갈을 한 건으로 만든다. 운영 설정(3)에서 같은 결말에 닿으려면
 * hang job 의 시도 3번이 필요한데(요구서 4-4 의 슬롯 산술), 여기서 재는 것은 "몇 건이면
 * 고갈되나"가 아니라 **고갈된 뒤에 무슨 일이 일어나나**다.
 */
@ActiveProfiles("test")
@SpringBootTest(
    properties = [
        "app.scheduling.enabled=true",
        "app.worker.enabled=true",
        "app.worker.concurrency=1",
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
class HangSlotStarvationTest @Autowired constructor(
    private val holdService: HoldService,
    private val jobRepository: JobRepository,
    private val userRepository: UserRepository,
    private val stubClient: GenerationStubClient,
    private val registry: MeterRegistry
) {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            SharedContainers.registerDatabase(registry, "inv05_starvation")
        }

        /**
         * 뒤에 선 job 을 지켜보는 시간. 슬롯이 하나뿐이고 그 하나가 영구히 묶였으므로
         * 이 값을 아무리 키워도 결과는 같다 — 그것이 "상한이 없다"의 뜻이다.
         */
        private const val OBSERVE_SECONDS = 8L
    }

    @Test
    fun `hang 이 슬롯을 다 먹으면 뒤에 줄 선 job 은 HOLDING 에서 늙기만 한다`() {
        val user = userRepository.save(User("inv05-starve", 1000L))

        val hangingJobId = holdService.requestGeneration(user.persistedId, "starve-hang", "cat").jobId

        try {
            // 슬롯 하나짜리 풀이 hang 에 통째로 묶일 때까지 기다린다.
            await().atMost(30, TimeUnit.SECONDS).untilAsserted {
                assertThat(registry.get(WorkerSlotMetrics.FREE_SLOTS_METRIC).gauge().value())
                    .describedAs("빈 슬롯")
                    .isZero()
            }

            val queuedJobId = holdService.requestGeneration(user.persistedId, "starve-queued", "cat").jobId

            // 지켜보는 내내 상태가 바뀌지 않는다. `during` 은 "그 구간 내내 참" 을 요구한다.
            await().during(OBSERVE_SECONDS, TimeUnit.SECONDS)
                .atMost(OBSERVE_SECONDS + 10, TimeUnit.SECONDS)
                .untilAsserted {
                    assertThat(jobRepository.findById(queuedJobId).orElseThrow().status)
                        .describedAs("뒤에 접수된 job — 선점조차 되지 않는다")
                        .isEqualTo(JobStatus.HOLDING)
                    assertThat(registry.get(WorkerSlotMetrics.FREE_SLOTS_METRIC).gauge().value())
                        .describedAs("슬롯은 회수되지 않는다 — 절대 상한은 돈만 풀고 스레드는 못 푼다")
                        .isZero()
                }

            // 앞선 hang job 도 마찬가지다. 절대 상한이 FAILED 로 내려 재시도를 투입하지만,
            // 그 재시도를 집을 슬롯이 없어 이 job 역시 HOLDING 에서 멈춘다.
            assertThat(jobRepository.findById(hangingJobId).orElseThrow().status)
                .describedAs("hang job 자신도 재투입 뒤 HOLDING 에 머문다 (concurrency=1 이라 자기 자리도 없다)")
                .isEqualTo(JobStatus.HOLDING)

            assertThat(userRepository.findById(user.persistedId).orElseThrow().balance)
                .describedAs("두 job 의 돈 200 이 묶인 채다. 언제 풀리는지에 대한 상한이 없다")
                .isEqualTo(1000L - 200L)
        } finally {
            stubClient.releaseHang()
        }

        // 스레드가 풀려야 슬롯이 돌아온다. 남겨 두면 공유 Redis 의 고아 heartbeat 가 다음 컨텍스트로 넘어간다.
        await().atMost(60, TimeUnit.SECONDS).untilAsserted {
            assertThat(registry.get(WorkerSlotMetrics.FREE_SLOTS_METRIC).gauge().value()).isEqualTo(1.0)
        }
    }
}
