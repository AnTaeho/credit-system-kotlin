package com.example.credit_system_kotlin.invariants

import com.example.credit_system_kotlin.job.repository.JobRepository
import com.example.credit_system_kotlin.ledger.event.LedgerReconciliationCompleted
import com.example.credit_system_kotlin.ledger.repository.LedgerRepository
import com.example.credit_system_kotlin.ledger.scheduling.LedgerReconciliationTask
import com.example.credit_system_kotlin.support.RecordingEventPublisher
import com.example.credit_system_kotlin.user.repository.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

/**
 * 부하 직후에 INV-01·INV-02 를 즉시 확인하는 검사다. 평소 테스트가 아니라 **측정 도구**다.
 *
 * 요구서 4-1 의 측정 도구 gap 을 닫는다 — 세 검사 모두 코드에는 있었지만, 부하를 받은 DB 에
 * 대고 지금 돌릴 수단이 없었다. 새 엔드포인트를 만들지 않았다. [LedgerReconciliationTask.reconcile]
 * 은 이미 public 이고 `@Scheduled` 가 직접 호출을 막지 않으므로, 그대로 부른다.
 *
 * **Testcontainers 를 쓰지 않는다.** 봐야 하는 것은 부하를 받은 그 DB, 즉 compose 로 띄운
 * localhost:3306/credit_system 이다. 그래서 `@ActiveProfiles("test")`(H2)를 붙이지 않고
 * `application.yml` 의 기본 datasource 를 그대로 쓴다.
 *
 * 워커와 스케줄러는 끈다. 켜 두면 이 검사 프로세스가 재고 있는 DB 를 **스스로 바꾼다** —
 * 워커가 HOLDING job 을 집어가고 정체 회수가 PROCESSING 을 FAILED 로 내린다. 측정 도구는
 * 대상에 손대지 않아야 한다. 스케줄러를 끄면 [LedgerReconciliationTask] 가 빈으로 뜨지 않으므로
 * (`@ConditionalOnProperty`) 여기서 직접 만들어 쓴다 — 발행된 이벤트를 [RecordingEventPublisher]
 * 로 그대로 받는 편이 지표를 스크레이프하는 것보다 짧다.
 *
 * 실행:
 *   docker compose up -d && ./gradlew postLoadCheck
 */
@Tag("post-load")
@SpringBootTest(
    properties = [
        "app.worker.enabled=false",
        "app.scheduling.enabled=false",
        "management.health.redis.enabled=false"
    ]
)
class PostLoadConsistencyCheck @Autowired constructor(
    private val ledgerRepository: LedgerRepository,
    private val jobRepository: JobRepository,
    private val userRepository: UserRepository
) {

    @Test
    fun `대사 결과에 불일치가 없다`() {
        val publisher = RecordingEventPublisher()
        LedgerReconciliationTask(ledgerRepository, userRepository, publisher).reconcile()

        val completed = publisher.events.filterIsInstance<LedgerReconciliationCompleted>().single()

        println(
            "INV-02 대사: checkedCount=${completed.checkedCount}, " +
                "mismatchCount=${completed.mismatchCount}, duration=${completed.duration}"
        )
        assertThat(completed.mismatchCount)
            .describedAs("원장 합계와 잔액이 어긋난 사용자 수 (검사한 사용자 %d명)", completed.checkedCount)
            .isZero()
    }

    @Test
    fun `HOLD 원장 없는 job 이 없다`() {
        val count = jobRepository.countJobsWithoutHoldEntry()

        println("INV-02 HOLD 누락 job=$count")
        assertThat(count).describedAs("HOLD 원장 없이 존재하는 job 수").isZero()
    }

    @Test
    fun `종결됐는데 정산 원장이 없는 job 이 없다`() {
        val count = jobRepository.countUnsettledTerminalJobs()

        println("INV-02 미정산 종결 job=$count")
        assertThat(count)
            .describedAs("COMPLETED 인데 CONFIRM 이 없거나 REFUNDED 인데 REFUND 가 없는 job 수")
            .isZero()
    }

    @Test
    fun `잔액이 음수인 사용자가 없다`() {
        val count = userRepository.countByBalanceLessThan(0L)

        println("INV-01 음수 잔액 사용자=$count")
        assertThat(count).describedAs("잔액이 음수인 사용자 수").isZero()
    }
}
