package com.example.credit_system_kotlin.observability

import com.example.credit_system_kotlin.global.exception.DuplicateRequestInProgressException
import com.example.credit_system_kotlin.global.exception.GlobalExceptionHandler
import com.example.credit_system_kotlin.global.exception.InsufficientBalanceException
import com.example.credit_system_kotlin.job.concurrency.SharedContainers
import com.example.credit_system_kotlin.job.concurrency.runConcurrently
import com.example.credit_system_kotlin.job.service.HoldService
import com.example.credit_system_kotlin.organization.domain.Organization
import com.example.credit_system_kotlin.organization.repository.OrganizationRepository
import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * 진짜 경쟁 상태에서 방어 카운터의 합이 맞는지 확인하는 통합 테스트다.
 *
 * `MeterRegistry` 는 Spring 컨텍스트에서 싱글턴이라 같은 컨텍스트를 공유하는 다른 테스트가
 * 이미 올려 둔 값이 남아 있다. 절대값을 단언하면 실행 순서에 따라 깨지는 플레이키 테스트가
 * 되므로, 시작 시점의 값을 찍어 두고 **증가분**만 단언한다.
 */
@ActiveProfiles("test")
@SpringBootTest
class DefenseMetricsConcurrencyTest @Autowired constructor(
    private val holdService: HoldService,
    private val organizationRepository: OrganizationRepository,
    private val globalExceptionHandler: GlobalExceptionHandler,
    private val registry: MeterRegistry
) {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasourceProps(registry: DynamicPropertyRegistry) {
            SharedContainers.registerDatabase(registry, "defense_metrics")
        }

        private const val COST = 100L
    }

    private fun count(point: String, outcome: String): Double =
        registry.find(DefenseMetrics.DEFENSE_METRIC)
            .tag("point", point)
            .tag("outcome", outcome)
            .counter()
            ?.count() ?: 0.0

    private fun snapshot(vararg combinations: Pair<String, String>): Map<Pair<String, String>, Double> =
        combinations.associateWith { (point, outcome) -> count(point, outcome) }

    private fun delta(before: Map<Pair<String, String>, Double>, point: String, outcome: String): Double =
        count(point, outcome) - (before[point to outcome] ?: 0.0)

    @Test
    fun `잔액이 일부만 감당하면 applied와 rejected의 합이 전체 시도 수가 된다`() {
        val threadCount = 10
        val affordable = 4
        val organization = organizationRepository.save(Organization("acme", COST * affordable))
        val before = snapshot("hold_balance" to "applied", "hold_balance" to "rejected")

        runConcurrently(threadCount) { idx ->
            try {
                holdService.requestGeneration(organization.persistedId, "defense-key-$idx", "cat")
            } catch (e: InsufficientBalanceException) {
                // 조건부 UPDATE 가 0행을 돌려준 정상 경로다.
            }
        }

        val applied = delta(before, "hold_balance", "applied")
        val rejected = delta(before, "hold_balance", "rejected")
        assertThat(applied).isEqualTo(affordable.toDouble())
        assertThat(rejected).isEqualTo((threadCount - affordable).toDouble())
        assertThat(applied + rejected).isEqualTo(threadCount.toDouble())

        // 정확히 감당 가능한 만큼만 빠졌다. 카운터와 실제 돈이 같은 이야기를 해야 한다.
        val found = organizationRepository.findById(organization.persistedId).orElseThrow()
        assertThat(found.balance).isZero()
    }

    /**
     * `db_unique` 는 서비스 밖(`GlobalExceptionHandler`)에서 발행된다. 여기서는 HTTP 를 태우는
     * 대신, 서비스에서 새어 나온 예외를 그 핸들러에 그대로 넘긴다 — DispatcherServlet 이
     * 하는 일과 같은 호출이다.
     */
    @Test
    fun `같은 idemKey로 동시 요청하면 한 건만 통과하고 나머지는 멱등키가 막는다`() {
        val threadCount = 10
        val organization = organizationRepository.save(Organization("acme", 10_000L))
        val before = snapshot(
            "hold_balance" to "applied",
            "idem_key" to "app_hit",
            "idem_key" to "db_unique"
        )

        runConcurrently(threadCount) {
            try {
                holdService.requestGeneration(organization.persistedId, "defense-shared-key", "cat")
            } catch (e: DuplicateRequestInProgressException) {
                // 선점한 쪽이 아직 jobId를 붙이기 전에 들어온 요청. 1차 방어가 잡은 정상 경로다.
            } catch (e: DataIntegrityViolationException) {
                globalExceptionHandler.handleDataIntegrityViolation(e)
            }
        }

        val appHit = delta(before, "idem_key", "app_hit")
        val dbUnique = delta(before, "idem_key", "db_unique")
        assertThat(appHit + dbUnique).isEqualTo((threadCount - 1).toDouble())
        assertThat(delta(before, "hold_balance", "applied")).isEqualTo(1.0)

        val found = organizationRepository.findById(organization.persistedId).orElseThrow()
        assertThat(found.balance).isEqualTo(10_000L - COST)
    }
}
