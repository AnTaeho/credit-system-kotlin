package com.example.credit_system_kotlin.invariants

import com.example.credit_system_kotlin.job.concurrency.SharedContainers
import com.example.credit_system_kotlin.job.domain.JobStatus
import com.example.credit_system_kotlin.job.repository.JobRepository
import com.example.credit_system_kotlin.job.service.HoldService
import com.example.credit_system_kotlin.ledger.domain.LedgerType
import com.example.credit_system_kotlin.ledger.event.LedgerReconciliationCompleted
import com.example.credit_system_kotlin.ledger.repository.LedgerRepository
import com.example.credit_system_kotlin.ledger.scheduling.LedgerReconciliationTask
import com.example.credit_system_kotlin.support.RecordingEventPublisher
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
 * **INV-08 — 외부 API 완전 장애. 장애 중 종결된 job 에 대해 Σ환불 = Σhold, 잔액 원상**
 * (요구서 4-7).
 *
 * **축소판이다.** 요구서는 10분(600초) 장애를 말하고, 재현 방식 (A)
 * (`app.stub.failure-rate: 1.0`, 지연 3~7초) 기준으로 그 안에 시도를 다 쓸 수 있는
 * **N 상한을 120** 으로 계산했다(`3N ≤ 600초 × 0.6 시도/s`).
 *
 * 여기서 고른 N 은 **20** 이다. 120 보다 훨씬 아래인 이유는 둘이다.
 * 1. 요구서의 120 은 "10분이 다 흐른다"는 전제의 상한이다. 전체 `./gradlew test` 예산 안에
 *    들어가려면 장애 구간 자체를 초 단위로 줄여야 하고, 그러려면 스텁 지연을 0으로 눕혀야 한다
 *    (`test` 프로파일 기본값). 시도 하나가 싸지면 같은 시간에 더 많이 처리되지만, 거꾸로
 *    **재는 시간이 짧아지므로 N 도 함께 줄여야 결과가 나온다.** 요구서가 "부하 프로파일마다
 *    N 을 다시 계산해야 한다"고 적은 그 지점이다.
 * 2. 120 은 상한이지 권장값이 아니다. job 마다 재시도 사이 backoff 가 끼므로 실제 소진은
 *    상한보다 느리고, 상한에 붙여 잡으면 "다 못 쓴 job" 이 남아 Σ환불 = Σhold 가 **설계상**
 *    거짓이 된다(요구서 4-7). 목표 (i) 를 재려면 여유 있게 아래로 잡아야 한다.
 *
 * 20 건 × 3 시도 = 60 시도, 슬롯 3개, 시도 하나가 거의 0초, backoff 1초 × 2회
 * (`test` 프로파일). 장애가 끝나기를 기다릴 필요도 없다 — 스텁이 끝까지 실패하므로 20건
 * 전부가 재시도를 소진하고 REFUNDED 로 종결한다. 그것이 요구서 목표 (ii) 의 상황이다.
 *
 * 단언은 넷이다.
 * - 20건 전부 REFUNDED
 * - **Σ환불 = Σhold** — 원장 부호 규약상 HOLD 는 음수, REFUND 는 양수라 `sum(REFUND) == -sum(HOLD)`
 * - 사용자 잔액이 투입 전과 같다
 * - INV-02 세 검사 0건 ([PostLoadConsistencyCheck] 와 같은 방식으로 직접 대사를 돌린다)
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
class TotalOutageRefundTest @Autowired constructor(
    private val holdService: HoldService,
    private val jobRepository: JobRepository,
    private val userRepository: UserRepository,
    private val ledgerRepository: LedgerRepository
) {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            SharedContainers.registerDatabase(registry, "inv08_outage")
        }

        private const val JOB_COUNT = 20
        private const val START_BALANCE = 10_000L
        private const val COST = 100L
    }

    @Test
    fun `외부가 완전히 죽은 동안 투입한 20건이 전부 환불되고 잔액이 원상으로 돌아온다`() {
        val user = userRepository.save(User("inv08", START_BALANCE))
        val userId = user.persistedId

        val jobIds = (1..JOB_COUNT).map {
            holdService.requestGeneration(userId, "inv08-key-$it", "cat").jobId
        }

        assertThat(userRepository.findById(userId).orElseThrow().balance)
            .describedAs("투입 직후에는 전부 묶여 있다")
            .isEqualTo(START_BALANCE - JOB_COUNT * COST)

        await().atMost(120, TimeUnit.SECONDS).untilAsserted {
            val statuses = jobRepository.findAllById(jobIds).map { it.status }
            assertThat(statuses).containsOnly(JobStatus.REFUNDED)
        }

        val entries = ledgerRepository.findByUserIdOrderByIdDesc(userId)
        val holdSum = entries.filter { it.type == LedgerType.HOLD }.sumOf { it.amount }
        val refundSum = entries.filter { it.type == LedgerType.REFUND }.sumOf { it.amount }

        assertThat(entries).describedAs("job 당 HOLD 1 + REFUND 1").hasSize(JOB_COUNT * 2)
        assertThat(refundSum)
            .describedAs("Σ환불 = Σhold (HOLD 는 음수로 기록되므로 부호를 뒤집어 비교한다)")
            .isEqualTo(-holdSum)
        assertThat(refundSum).isEqualTo(JOB_COUNT * COST)
        assertThat(userRepository.findById(userId).orElseThrow().balance)
            .describedAs("모든 잔액이 투입 전과 같다")
            .isEqualTo(START_BALANCE)

        // INV-02 세 검사 — 장애가 지나간 DB 에 대고 즉시 돌린다.
        val publisher = RecordingEventPublisher()
        LedgerReconciliationTask(ledgerRepository, userRepository, publisher).reconcile()
        val completed = publisher.events.filterIsInstance<LedgerReconciliationCompleted>().single()
        assertThat(completed.mismatchCount).describedAs("대사 불일치").isZero()
        assertThat(jobRepository.countJobsWithoutHoldEntry()).describedAs("HOLD 누락 job").isZero()
        assertThat(jobRepository.countUnsettledTerminalJobs()).describedAs("미정산 종결 job").isZero()
    }
}
