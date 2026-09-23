package com.example.credit_system_kotlin.invariants

import com.example.credit_system_kotlin.global.exception.DuplicateRequestInProgressException
import com.example.credit_system_kotlin.job.concurrency.SharedContainers
import com.example.credit_system_kotlin.job.concurrency.runConcurrently
import com.example.credit_system_kotlin.job.dto.HoldResult
import com.example.credit_system_kotlin.job.repository.JobRepository
import com.example.credit_system_kotlin.job.service.HoldService
import com.example.credit_system_kotlin.ledger.repository.LedgerRepository
import com.example.credit_system_kotlin.user.domain.User
import com.example.credit_system_kotlin.user.repository.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * **INV-03 — 같은 `(userId, idemKey)` 로 N 회 요청해도 차감은 정확히 1회, 같은 `jobId` 를 돌려준다.**
 *
 * 기존 [com.example.credit_system_kotlin.job.concurrency.DuplicateIdemKeyTest] 가 10 병렬에서
 * "job 1개·원장 1개"를 본다. 여기서 두 가지를 더 한다.
 *
 * 1. **100 병렬**로 올린다.
 * 2. **응답을 분류한다.** 기존 테스트는 [runConcurrently] 안에서
 *    [DuplicateRequestInProgressException] 만 잡는데, 그 헬퍼는 `Future` 를 회수하지 않으므로
 *    **다른 예외가 나도 조용히 통과한다.** 무엇이 나왔는지를 세지 않으면 "차감 1회"는 알아도
 *    "나머지 99 개는 무엇을 받았나"는 모른다. 요구서 4-2 가 k6 집계 규칙으로 못박은 자리가
 *    바로 이 분류다.
 *
 * **나올 수 있는 결과는 넷이고, 그중 셋이 정상이다.**
 * - 선점한 하나: `HoldResult(duplicate=false)` — 차감이 일어난 유일한 요청
 * - 선점 뒤·`attachJobId` 전에 들어온 요청: [DuplicateRequestInProgressException]
 *   → HTTP 409 / `DUPLICATE_IN_PROGRESS`
 * - 첫 트랜잭션이 커밋된 뒤에 들어온 요청: `HoldResult(duplicate=true)` + **같은 jobId**
 * - `findByUserIdAndIdemKey` 가 비어 있다고 본 직후 INSERT 가 유니크 키에 부딪힌 요청:
 *   [DataIntegrityViolationException] → 같은 409 / `DUPLICATE_IN_PROGRESS`
 *   (`GlobalExceptionHandler.handleDataIntegrityViolation`, 요구서 4-2)
 *
 * **실측(2026-09-23, 100 병렬) 분포: 신규 1 / 중복응답 90 / DUPLICATE_IN_PROGRESS 0 / 유니크제약 9.**
 *
 * `DUPLICATE_IN_PROGRESS` 가 0 인 것은 우연이 아니라 **구조**로 보인다.
 * `requestGeneration` 은 트랜잭션 하나이고 `attachJobId` 가 그 안에서 끝나므로,
 * **`jobId` 가 비어 있는 `idempotency_keys` 행은 커밋된 적이 없다.** 다른 스레드가 보는 것은
 * "아무것도 없음"(→ 자기 INSERT 가 유니크 키에 막혀 [DataIntegrityViolationException])이거나
 * `jobId` 가 이미 채워진 행(→ `duplicate=true`)뿐이다. 서비스 코드 어디에도 `REQUIRES_NEW`
 * 같은 별도 트랜잭션이 없다(2026-09-23 확인). 즉 이 설계에서 `resolveDuplicateRequest` 의
 * 던지는 갈래는 동시성만으로는 닿지 않고, 실제로 409 를 내는 자리는 **DB 유니크 키**다.
 * 요구서 4-2 와 인벤토리 3-1 S1 의 서술("INSERT 한 뒤 attachJobId 전에 들어오면 던진다")은
 * 그래서 다시 볼 자리다 — 문서 수정은 이 조각의 범위 밖이라 여기 사실만 적어 둔다.
 *
 * 분포 자체는 고정하지 않는다. 고정하는 것은 **갈래의 집합**이고, 그 집합은 어느 쪽이 나오든
 * 전부 409 `DUPLICATE_IN_PROGRESS` 하나로 나간다.
 *
 * 넷 다 "차감은 한 번"이라는 불변식을 지킨 결과다. **그 밖의 예외는 0이어야 한다** — 하나라도
 * 있으면 오류율 집계가 오염되고, 요구서 4-2 의 "409 두 종류를 코드로 가른다"는 규칙이 깨진다.
 *
 * 워커는 `test` 프로파일 기본값대로 꺼 둔다. 켜면 job 이 소비되어 원장이 늘어난다.
 */
@ActiveProfiles("test")
@SpringBootTest(
    properties = [
        "app.worker.enabled=false",
        "app.scheduling.enabled=false",
        // 살아 있는 컨텍스트마다 풀을 쥐고 있으므로 공유 컨테이너의 커넥션을 아껴 쓴다.
        "spring.datasource.hikari.maximum-pool-size=5",
        "spring.datasource.hikari.connection-timeout=60000"
    ]
)
class IdempotencyUnderConcurrencyTest @Autowired constructor(
    private val holdService: HoldService,
    private val jobRepository: JobRepository,
    private val ledgerRepository: LedgerRepository,
    private val userRepository: UserRepository
) {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            SharedContainers.registerDatabase(registry, "inv03_idem")
        }

        private const val THREAD_COUNT = 100
        private const val START_BALANCE = 10_000L
        private const val COST = 100L
    }

    @Test
    fun `같은 키로 100번 동시에 요청해도 차감은 한 번이고 응답은 세 정상 갈래로만 갈린다`() {
        val user = userRepository.save(User("inv03", START_BALANCE))
        val idemKey = "inv03-shared-key"

        val results = ConcurrentLinkedQueue<HoldResult>()
        val duplicateInProgress = ConcurrentLinkedQueue<Throwable>()
        val uniqueViolation = ConcurrentLinkedQueue<Throwable>()
        val unexpected = ConcurrentLinkedQueue<Throwable>()

        runConcurrently(THREAD_COUNT, 120) {
            try {
                results += holdService.requestGeneration(user.persistedId, idemKey, "cat")
            } catch (e: DuplicateRequestInProgressException) {
                duplicateInProgress += e
            } catch (e: DataIntegrityViolationException) {
                uniqueViolation += e
            } catch (e: RuntimeException) {
                unexpected += e
            }
        }

        assertThat(unexpected)
            .describedAs("409 두 갈래 밖의 예외 — 하나라도 있으면 오류율 집계가 오염된다")
            .isEmpty()
        assertThat(results.size + duplicateInProgress.size + uniqueViolation.size)
            .describedAs("끝까지 간 요청 수")
            .isEqualTo(THREAD_COUNT)

        val fresh = results.filter { !it.duplicate }
        val duplicates = results.filter { it.duplicate }
        assertThat(fresh)
            .describedAs("차감을 일으킨 요청은 정확히 하나여야 한다")
            .hasSize(1)
        val jobId = fresh.single().jobId
        assertThat(duplicates.map { it.jobId }.distinct())
            .describedAs("뒤늦게 온 요청은 모두 **같은 jobId** 를 돌려받는다 (INV-03 의 후반부)")
            .isSubsetOf(listOf(jobId))

        assertThat(jobRepository.findByUserIdOrderByIdDesc(user.persistedId))
            .describedAs("job 은 하나")
            .hasSize(1)
        assertThat(ledgerRepository.findByUserIdOrderByIdDesc(user.persistedId))
            .describedAs("원장도 하나 — HOLD 한 줄뿐")
            .hasSize(1)
        assertThat(userRepository.findById(user.persistedId).orElseThrow().balance)
            .describedAs("차감은 정확히 1회")
            .isEqualTo(START_BALANCE - COST)

        println(
            "INV-03 응답 분류(threads=$THREAD_COUNT): 신규=${fresh.size}, " +
                "중복응답=${duplicates.size}, DUPLICATE_IN_PROGRESS=${duplicateInProgress.size}, " +
                "유니크제약=${uniqueViolation.size}"
        )
    }
}
