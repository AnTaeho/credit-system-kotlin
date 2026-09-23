package com.example.credit_system_kotlin.invariants

import com.example.credit_system_kotlin.job.concurrency.SharedContainers
import com.example.credit_system_kotlin.job.domain.Job
import com.example.credit_system_kotlin.job.domain.JobStatus
import com.example.credit_system_kotlin.job.repository.JobRepository
import com.example.credit_system_kotlin.ledger.domain.LedgerEntry
import com.example.credit_system_kotlin.ledger.event.LedgerReconciliationCompleted
import com.example.credit_system_kotlin.ledger.repository.LedgerRepository
import com.example.credit_system_kotlin.ledger.scheduling.LedgerReconciliationTask
import com.example.credit_system_kotlin.support.RecordingEventPublisher
import com.example.credit_system_kotlin.user.domain.User
import com.example.credit_system_kotlin.user.repository.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.time.Instant

/**
 * **INV-02 의 반대 방향 — 세 검사가 실제로 불일치를 잡는지** (요구서 4-1).
 *
 * 지금까지 있던 증명은 전부 "맞을 때 0건"이었다
 * ([com.example.credit_system_kotlin.ledger.scheduling.LedgerReconciliationTaskTest],
 * [com.example.credit_system_kotlin.global.scheduling.DomainSnapshotTaskTest],
 * [PostLoadConsistencyCheck]). 0건은 **검사가 눈이 멀었을 때도** 나오는 값이다.
 * 그래서 여기서는 셋을 일부러 깨뜨리고 각각이 1 이상을 내는지 본다. 이 테스트가 없으면
 * 부하 직후의 "mismatch 0건"이 무엇을 증명하는지 말할 수 없다.
 *
 * **왜 Testcontainers 인가.** 세 검사는 전부 **DB 전체 집계**다. H2(`credit_test`)는 이 JVM 의
 * 모든 캐시된 컨텍스트가 공유하므로 다른 테스트가 남긴 행이 셈에 섞인다. 자기 DB 를 쓴다.
 *
 * **왜 절대값이 아니라 증감을 단언하는가.** 같은 이유로, 그리고 V6 트리거가 `ledger_entries`
 * 의 DELETE 를 막아([LedgerAppendOnlyTest]) 흔한 `deleteAll()` 정리를 쓸 수 없기 때문이다.
 * 기준선을 먼저 재고 "기준선 + 1" 을 단언하면 실행 순서와 무관해진다.
 *
 * **틀어 놓는 자리는 `users` 다.** 원장은 V6 가 막지만 `users.balance` 는 막지 않는다 —
 * 조건부 UPDATE 가 서비스의 정상 경로라 막을 수 없다. 운영에서 대사가 잡아야 할 사고도
 * "원장에 없는 잔액 변동"이므로 재현 방향이 맞다.
 *
 * 스케줄러는 `test` 프로파일에서 꺼져 있어 [LedgerReconciliationTask] 가 빈으로 뜨지 않는다.
 * [PostLoadConsistencyCheck] 와 같은 방식으로 직접 만들어 부른다.
 */
@ActiveProfiles("test")
@SpringBootTest
class ReconciliationDetectsMismatchTest @Autowired constructor(
    private val ledgerRepository: LedgerRepository,
    private val jobRepository: JobRepository,
    private val userRepository: UserRepository,
    private val jdbcTemplate: JdbcTemplate
) {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            SharedContainers.registerDatabase(registry, "inv02_mismatch")
        }
    }

    private fun reconcileMismatchCount(): Long {
        val publisher = RecordingEventPublisher()
        LedgerReconciliationTask(ledgerRepository, publisher).reconcile()
        return publisher.events.filterIsInstance<LedgerReconciliationCompleted>().single().mismatchCount.toLong()
    }

    /** 검사 (a) — `balance == initialBalance + Σledger`. */
    @Test
    fun `원장을 거치지 않고 잔액을 바꾸면 대사가 그 사용자를 잡아낸다`() {
        val baseline = reconcileMismatchCount()

        // 원장이 한 줄도 없는 사용자는 balance == initialBalance 라 대사에 맞는다.
        // 그 상태에서 원장을 거치지 않고 잔액만 깎는다.
        val user = userRepository.save(User("inv02-a", 1000L))
        jdbcTemplate.update("UPDATE users SET balance = balance - 100 WHERE id = ?", user.persistedId)

        assertThat(reconcileMismatchCount())
            .describedAs("원장이 없으니 1000 이어야 할 잔액을 900 으로 틀어 놓았다")
            .isEqualTo(baseline + 1)

        // 되돌리면 다시 맞는다 — 검사가 "아무나 걸고 보는" 것이 아님을 같은 자리에서 보인다.
        jdbcTemplate.update("UPDATE users SET balance = balance + 100 WHERE id = ?", user.persistedId)
        assertThat(reconcileMismatchCount())
            .describedAs("틀어 놓은 것을 되돌리면 기준선으로 돌아온다")
            .isEqualTo(baseline)
    }

    /** 검사 (b) — HOLD 원장이 없는 job 0건. */
    @Test
    fun `HOLD 원장 없이 job 만 만들면 countJobsWithoutHoldEntry 가 잡아낸다`() {
        val baseline = jobRepository.countJobsWithoutHoldEntry()

        val user = userRepository.save(User("inv02-b", 1000L))
        jobRepository.saveAndFlush(Job.hold(user.persistedId, 100L, "cat"))

        assertThat(jobRepository.countJobsWithoutHoldEntry())
            .describedAs("HOLD 원장 없이 존재하는 job")
            .isEqualTo(baseline + 1)
    }

    /** 검사 (c) — 미정산 종결 job 0건. */
    @Test
    fun `CONFIRM 원장 없이 COMPLETED 로 올리면 countUnsettledTerminalJobs 가 잡아낸다`() {
        val baseline = jobRepository.countUnsettledTerminalJobs()

        val user = userRepository.save(User("inv02-c", 1000L))
        val job = jobRepository.saveAndFlush(Job.hold(user.persistedId, 100L, "cat"))
        val jobId = job.persistedId
        ledgerRepository.saveAndFlush(LedgerEntry.hold(user.persistedId, jobId, 100L))

        // 정상 경로(JobLifecycleService.confirm)는 CONFIRM 원장을 같은 트랜잭션에서 남긴다.
        // 그 한 줄만 빠뜨린 상태 = C5 가 원장 INSERT 직전에 죽었는데 롤백도 안 된 세계다.
        jobRepository.startProcessingIfAttemptMatches(jobId, 0, Instant.now())
        jobRepository.completeIfAttemptMatches(jobId, "https://stub-images.local/x.png", 0, Instant.now())
        assertThat(jobRepository.findById(jobId).orElseThrow().status).isEqualTo(JobStatus.COMPLETED)

        assertThat(jobRepository.countUnsettledTerminalJobs())
            .describedAs("COMPLETED 인데 CONFIRM 원장이 없는 job")
            .isEqualTo(baseline + 1)
    }
}
