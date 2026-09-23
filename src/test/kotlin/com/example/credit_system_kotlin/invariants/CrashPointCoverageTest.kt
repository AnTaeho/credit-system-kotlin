package com.example.credit_system_kotlin.invariants

import com.example.credit_system_kotlin.job.domain.JobStatus
import com.example.credit_system_kotlin.job.repository.JobRepository
import com.example.credit_system_kotlin.job.service.HoldService
import com.example.credit_system_kotlin.job.service.JobLifecycleService
import com.example.credit_system_kotlin.ledger.domain.LedgerEntry
import com.example.credit_system_kotlin.ledger.domain.LedgerType
import com.example.credit_system_kotlin.ledger.repository.LedgerRepository
import com.example.credit_system_kotlin.user.domain.User
import com.example.credit_system_kotlin.user.repository.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import java.time.Instant

/**
 * **INV-07 — 프로세스가 어느 지점에서 죽어도 재기동 후 INV-02 세 검사가 0건이고 묶인 돈은
 * 회수 경로로 풀린다** (요구서 4-6). 인벤토리 3-2 의 C1~C11 을 **어디서 덮는지의 표**가 여기다.
 *
 * - **C1** TX-1 도중, 커밋 전 — `ServiceTransactionRollbackTest`
 *   "잔액 부족으로 hold 가 실패하면 선점한 멱등 키도 롤백된다"
 * - **C2** TX-1 커밋 직후, HTTP 응답 전 — **재현 불가**(프로세스 kill 필요).
 *   같은 키 재요청이 같은 jobId 를 받는다는 절반은 [IdempotencyUnderConcurrencyTest]
 * - **C3a** 선점 CAS 후 `startHeartbeat` 전 — **재현 불가**(스레드 경계 주입).
 *   회수 경로 자체는 `DeadJobRecoveryTaskTest` 의 BACKSTOP 케이스
 * - **C3b** `startHeartbeat` 후 외부 호출 전 — **재현 불가**(주입). 회수 경로는 C3a 와 같다
 * - **C3c** 외부 호출 전송 후 응답 전 — [CrashPointInjectionTest] "C3c",
 *   `GenerationTimeoutRefundTest`. 외부 부작용 미지 문제는 INV-04b([ExternalCallBudgetTest])
 * - **C4** 외부 호출 성공 후 `confirm` 전 — [CrashPointInjectionTest] "C4".
 *   프로세스 사망 판은 `deploy/observability/scenarios/01-worker-crash.sh`
 * - **C5** `confirm` CAS 성공 후 CONFIRM 원장 INSERT 전 — **이 파일** + `JobLifecycleServiceTest`
 * - **C6** `finalRefund` 트랜잭션 도중 — `ServiceTransactionRollbackTest`
 *   "환불할 사용자가 사라졌으면 REFUNDED 전이도 롤백된다"
 * - **C7** 워커 스레드 hang — `HangAbsoluteTimeoutTest`(concurrency=4),
 *   [OutstandingMoneyBoundTest], [HangSlotStarvationTest]
 * - **C8** Redis 장애 중 워커 사망 — `HeartbeatRegistryTest`,
 *   `DeadJobRecoveryTaskTest`(BACKSTOP_BLIND). 워커 사망 조합은 **재현 불가**
 * - **C9** 스케줄러가 돌지 않는 상태 — **이 파일**. 자동 복구 경로가 없다는 사실을 고정한다
 * - **C10** 선점 후 executor 거부 + 롤백도 실패 — `GenerationWorkerUnitTest`
 * - **C11** SIGTERM 드레인 상한 초과 — `GenerationDrainOnShutdownTest`
 *
 * **재현 불가로 남는 것은 C2·C3a·C3b, 그리고 C8 의 "워커까지 죽는" 조합이다.** 전부
 * 프로세스를 죽이거나 스레드 중간을 끊어야 하는 지점이고, in-JVM 테스트의 경계 밖이다.
 * 그 자리는 셸 시나리오(`deploy/observability/scenarios/`)의 몫으로 남긴다.
 *
 * 워커·스케줄러는 `test` 프로파일 기본값대로 꺼져 있다. 여기서 보는 것은 트랜잭션 경계와
 * "아무도 안 돌 때 무슨 일이 일어나지 않는가"이므로, 배경에서 도는 것이 있으면 안 된다.
 */
@ActiveProfiles("test")
@SpringBootTest
class CrashPointCoverageTest @Autowired constructor(
    private val holdService: HoldService,
    private val jobLifecycleService: JobLifecycleService,
    private val jobRepository: JobRepository,
    private val userRepository: UserRepository
) {

    /**
     * CONFIRM 원장 INSERT 만 실패시키는 자리. `confirm` 의 트랜잭션은 CAS UPDATE 와 이 INSERT
     * 둘뿐이라, INSERT 를 던지게 하면 C5 지점에서 죽은 것과 같은 경계가 된다.
     */
    @MockitoSpyBean
    private lateinit var ledgerRepository: LedgerRepository

    /**
     * **C5 — `confirm` CAS 는 성공했는데 CONFIRM 원장 INSERT 가 죽는다.**
     *
     * 단일 `@Transactional` 이라 COMPLETED 전이까지 통째로 롤백된다. 그래서 남는 상태는
     * "COMPLETED 인데 CONFIRM 원장이 없는 job"(= INV-02 검사 (c) 위반)이 **아니라**
     * 그냥 PROCESSING 이고, 정체 회수가 집어 간다. 이것이 C5 가 통과인 이유다.
     */
    @Test
    fun `C5 - CONFIRM 원장 INSERT 가 실패하면 COMPLETED 전이도 함께 롤백된다`() {
        val user = userRepository.save(User("c5", 1000L))
        val jobId = holdService.requestGeneration(user.persistedId, "c5-key", "cat").jobId
        jobRepository.startProcessingIfAttemptMatches(jobId, 0, Instant.now())
        val processing = jobRepository.findById(jobId).orElseThrow()

        doThrow(IllegalStateException("C5 주입: CONFIRM 원장 INSERT 직전에 죽는다"))
            .whenever(ledgerRepository).save(any<LedgerEntry>())

        assertThatThrownBy { jobLifecycleService.confirm(processing, "https://stub-images.local/c5.png") }
            .isInstanceOf(IllegalStateException::class.java)

        val job = jobRepository.findById(jobId).orElseThrow()
        assertThat(job.status)
            .describedAs("COMPLETED 로 남으면 미정산 종결 job 이 된다 — 롤백이 그것을 막는다")
            .isEqualTo(JobStatus.PROCESSING)
        assertThat(job.resultUrl).isNull()
        assertThat(jobRepository.countUnsettledTerminalJobs())
            .describedAs("INV-02 검사 (c) 가 0 이어야 한다")
            .isZero()
    }

    /**
     * **C9 — 스케줄러가 돌지 않는다.** 깨진 것을 그대로 단언하는 테스트다.
     *
     * 인벤토리 3-2 C9 와 요구서 4-6 이 사실로 적어 둔 gap 을 고정한다: **자동 복구 경로가
     * 코드에 없다.** 스케줄러가 멈추면 FAILED job 은 재시도도 환불도 받지 못하고 그대로 늙는다.
     * 게이지(`DomainSnapshotTask` 의 미결 수·최고령)는 올라가지만 그 값을 보고 무언가 하는
     * 코드는 없다. 버그를 재현한 것이 아니라 **범위 밖이라고 적어 둔 것을 테스트로 옮긴 것**이다.
     */
    @Test
    fun `C9 - 스케줄러가 꺼져 있으면 FAILED job 은 아무도 집어 가지 않는다`() {
        val user = userRepository.save(User("c9", 1000L))
        val jobId = holdService.requestGeneration(user.persistedId, "c9-key", "cat").jobId
        jobRepository.startProcessingIfAttemptMatches(jobId, 0, Instant.now())
        jobRepository.failIfProcessing(jobId, 0, Instant.now())

        Thread.sleep(OBSERVE_MILLIS)

        val job = jobRepository.findById(jobId).orElseThrow()
        assertThat(job.status)
            .describedAs("재시도도 환불도 없다. 회수의 유일한 주체가 @Scheduled 스캔뿐이기 때문이다")
            .isEqualTo(JobStatus.FAILED)
        assertThat(userRepository.findById(user.persistedId).orElseThrow().balance)
            .describedAs("돈은 묶인 채 남는다")
            .isEqualTo(900L)

        // 그래도 INV-02 는 깨지지 않는다 — 원장과 잔액은 서로 맞다. FAILED 는 종결 상태가 아니다.
        assertThat(
            ledgerRepository.findByUserIdOrderByIdDesc(user.persistedId).map { it.type }
        ).isEqualTo(listOf(LedgerType.HOLD))
        assertThat(jobRepository.countUnsettledTerminalJobs()).isZero()
    }

    private companion object {
        /**
         * 지켜보는 시간. 길이는 본질이 아니다 — `app.scheduling.enabled=false` 면
         * `DeadJobRecoveryTask` 는 `@ConditionalOnProperty` 때문에 **빈으로 뜨지도 않는다.**
         * 즉 얼마를 기다리든 결과는 같다. 그 사실을 짧게 확인하는 값이다.
         */
        const val OBSERVE_MILLIS = 1500L
    }
}
