package com.example.credit_system_kotlin.invariants

import com.example.credit_system_kotlin.global.exception.InsufficientBalanceException
import com.example.credit_system_kotlin.job.concurrency.SharedContainers
import com.example.credit_system_kotlin.job.concurrency.runConcurrently
import com.example.credit_system_kotlin.job.repository.JobRepository
import com.example.credit_system_kotlin.job.service.HoldService
import com.example.credit_system_kotlin.ledger.repository.LedgerRepository
import com.example.credit_system_kotlin.user.domain.User
import com.example.credit_system_kotlin.user.repository.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * **INV-01 — 어떤 동시성에서도 `users.balance` 는 음수가 되지 않는다** (요구서 4절 INV 표).
 *
 * 기존 [com.example.credit_system_kotlin.job.concurrency.ConcurrentHoldTest] 가 같은 불변식을
 * 10 스레드로 증명한다. 여기서는 지시문이 요구한 규모 — **잔액 100, cost 1, 동시 1,000 요청** —
 * 로 올린다. 규모를 올리면 새로 드러나는 것은 "잔액이 딱 떨어지는 경계"다. 100회만 성공하고
 * 나머지 900 은 전부 잔액 부족으로 거절되며, 잔액은 음수가 아니라 정확히 0 이어야 한다.
 *
 * **`cost` 를 1 로 낮춘 이유.** 기본값 100 이면 잔액 100 으로는 1건밖에 성공하지 못해
 * "정확히 N건 성공" 이 경계를 재지 못한다. `app.generation.cost` 는 프로퍼티라 여기서만 바꾼다.
 *
 * **재는 것은 성공 수와 잔액뿐이다.** 1,000 동시는 Hikari 풀(여기서는 5)과 `users` 한 행의 락에서
 * 직렬화되므로 지연이 길어진다. 그 지연은 이 테스트의 관심사가 아니다(PERF 항목의 일이다).
 * 대신 두 가지를 막아 둔다.
 * - `connection-timeout` 을 올린다. `application-test.yml` 이 5초로 못박아 둔 값 그대로 두면
 *   커넥션 대기만으로 실패가 나서 "잔액 불변식이 깨졌다"와 구분되지 않는다.
 * - [runConcurrently] 의 대기 상한을 올리고, 결과를 **세 갈래로 세어 합계를 단언**한다.
 *   그 헬퍼는 상한을 넘겨도 예외를 던지지 않고 `Future` 가 예외를 삼키므로, 합계 단언이
 *   없으면 절반만 끝난 실행이 조용히 통과할 수 있다.
 *
 * 워커와 스케줄러는 `test` 프로파일 기본값대로 꺼져 있다. 켜져 있으면 워커가 job 을 집어가
 * 상태를 바꾸므로 잔액 경계를 재는 자리가 흔들린다.
 */
@ActiveProfiles("test")
@SpringBootTest(
    properties = [
        "app.generation.cost=1",
        "app.worker.enabled=false",
        "app.scheduling.enabled=false",
        "spring.datasource.hikari.connection-timeout=60000",
        // 살아 있는 컨텍스트마다 풀을 쥐고 있으므로 공유 컨테이너의 커넥션을 아껴 쓴다.
        "spring.datasource.hikari.maximum-pool-size=5"
    ]
)
class BalanceNeverNegativeTest @Autowired constructor(
    private val holdService: HoldService,
    private val userRepository: UserRepository,
    private val jobRepository: JobRepository,
    private val ledgerRepository: LedgerRepository
) {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            SharedContainers.registerDatabase(registry, "inv01_balance")
        }

        private const val THREAD_COUNT = 1000
        private const val START_BALANCE = 100L
        private const val COST = 1L
        private const val WAIT_SECONDS = 180L
    }

    @Test
    fun `잔액 100에 1짜리 요청 1000개가 동시에 와도 정확히 100개만 성공하고 잔액은 0이다`() {
        val user = userRepository.save(User("inv01", START_BALANCE))

        val succeeded = AtomicInteger()
        val rejected = AtomicInteger()
        val unexpected = ConcurrentLinkedQueue<Throwable>()

        runConcurrently(THREAD_COUNT, WAIT_SECONDS) { idx ->
            try {
                holdService.requestGeneration(user.persistedId, "inv01-key-$idx", "cat")
                succeeded.incrementAndGet()
            } catch (e: InsufficientBalanceException) {
                rejected.incrementAndGet()
            } catch (e: RuntimeException) {
                unexpected += e
            }
        }

        assertThat(unexpected)
            .describedAs("잔액 부족 말고 다른 이유로 실패한 요청 — 하나라도 있으면 경계를 잰 것이 아니다")
            .isEmpty()
        assertThat(succeeded.get() + rejected.get())
            .describedAs("끝까지 간 요청 수. 상한 %d초 안에 전원이 끝나야 한다", WAIT_SECONDS)
            .isEqualTo(THREAD_COUNT)
        assertThat(succeeded.get())
            .describedAs("잔액 %d / cost %d 이므로 성공은 정확히 %d 건", START_BALANCE, COST, START_BALANCE)
            .isEqualTo(START_BALANCE.toInt())

        val found = userRepository.findById(user.persistedId).orElseThrow()
        assertThat(found.balance).describedAs("음수가 아니라 정확히 0").isZero()

        // 성공한 요청마다 job 1 + HOLD 원장 1. 차감만 되고 기록이 없는 경로는 INV-02 의 관심사지만,
        // 경계에서 그 둘이 함께 움직였는지는 여기서 한 번에 볼 수 있다.
        assertThat(jobRepository.findByUserIdOrderByIdDesc(user.persistedId))
            .describedAs("성공 수만큼의 job")
            .hasSize(START_BALANCE.toInt())
        assertThat(ledgerRepository.findByUserIdOrderByIdDesc(user.persistedId))
            .describedAs("성공 수만큼의 HOLD 원장")
            .hasSize(START_BALANCE.toInt())
    }
}
