package com.example.credit_system_kotlin.invariants

import com.example.credit_system_kotlin.job.concurrency.SharedContainers
import com.example.credit_system_kotlin.job.domain.JobStatus
import com.example.credit_system_kotlin.job.repository.JobRepository
import com.example.credit_system_kotlin.job.service.HoldService
import com.example.credit_system_kotlin.observability.ExternalCallMetrics
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
 * **INV-04b — 중복 외부 호출은 막을 수 없으니 센다. 그리고 그 수에는 상한이 있다**
 * (요구서 4-3, 2026-09-23 확정).
 *
 * 외부 API 에 멱등키가 없다고 가정하므로 재시도마다 원가가 다시 나간다. 불변식으로 세울 수
 * 없는 대신 **지표**로 세우고, 지표에 상한을 건다 — job 당 `app.generation.max-attempts` 회다.
 * 세는 장치는 3-A 가 만든 [ExternalCallMetrics] 와
 * [com.example.credit_system_kotlin.job.event.ExternalGenerationCalled] 다.
 *
 * 여기서 고정하는 것은 셋이다.
 * - 한 job 을 끝까지 돌렸을 때 외부 호출 수 = 3 (= `max-attempts`)
 * - 그중 중복(`attemptNo > 0`) = 2
 * - **종결 뒤에는 더 늘지 않는다** — 환불이 끝난 job 이 어떤 경로로도 다시 외부를 부르지 않는다.
 *
 * 카운터는 컨텍스트마다 새로 뜨는 [MeterRegistry] 에 달려 있고 이 컨텍스트에는 job 이 하나뿐이라
 * 전역 카운터를 그대로 "job 당" 으로 읽을 수 있다.
 */
@ActiveProfiles("test")
@SpringBootTest(
    properties = [
        "app.scheduling.enabled=true",
        "app.worker.enabled=true",
        "app.stub.failure-rate=1.0",
        "app.processing.timeout-seconds=1",
        "app.processing.absolute-timeout-seconds=3",
        "app.scheduling.worker-interval-millis=100",
        "app.scheduling.dead-job-scan-interval-millis=500",
        // 살아 있는 컨텍스트마다 풀을 쥐고 있으므로 공유 컨테이너의 커넥션을 아껴 쓴다.
        "spring.datasource.hikari.maximum-pool-size=5"
    ]
)
class ExternalCallBudgetTest @Autowired constructor(
    private val holdService: HoldService,
    private val jobRepository: JobRepository,
    private val userRepository: UserRepository,
    private val registry: MeterRegistry
) {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            SharedContainers.registerDatabase(registry, "inv04b_budget")
        }

        private const val MAX_ATTEMPTS = 3
    }

    private fun calls(): Double = registry.get(ExternalCallMetrics.CALLS_METRIC).counter().count()

    private fun duplicateCalls(): Double =
        registry.get(ExternalCallMetrics.DUPLICATE_CALLS_METRIC).counter().count()

    @Test
    fun `외부가 계속 실패해도 job 하나가 쓰는 외부 호출은 3회를 넘지 않고 그중 2회가 중복이다`() {
        assertThat(calls()).describedAs("투입 전에는 0 — 시계열이 미리 등록돼 있다").isZero()

        val user = userRepository.save(User("inv04b", 1000L))
        val jobId = holdService.requestGeneration(user.persistedId, "inv04b-key", "cat").jobId

        await().atMost(60, TimeUnit.SECONDS).untilAsserted {
            assertThat(jobRepository.findById(jobId).orElseThrow().status).isEqualTo(JobStatus.REFUNDED)
        }

        assertThat(calls())
            .describedAs("job 당 외부 호출 상한 = max-attempts(%d)", MAX_ATTEMPTS)
            .isEqualTo(MAX_ATTEMPTS.toDouble())
        assertThat(duplicateCalls())
            .describedAs("첫 호출을 뺀 나머지가 중복 원가다")
            .isEqualTo((MAX_ATTEMPTS - 1).toDouble())

        // 종결 뒤에도 늘지 않는지 확인한다. 회수 스캔이 0.5초마다 도므로 그 몇 배를 지켜본다.
        val after = calls()
        await().during(3, TimeUnit.SECONDS).atMost(6, TimeUnit.SECONDS).untilAsserted {
            assertThat(calls()).describedAs("환불이 끝난 job 은 다시 외부를 부르지 않는다").isEqualTo(after)
        }
    }
}
