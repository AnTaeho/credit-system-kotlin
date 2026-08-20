package com.example.credit_system_kotlin.benchmark

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class BenchmarkHarnessTest {

    @Test
    @Timeout(30)
    @DisplayName("모든 워커가 완주하면 요청 수만큼 집계된다")
    fun aggregatesEveryRequestWhenAllWorkersFinish() {
        val strategy = InMemoryStrategy(10_000L)

        val result = BenchmarkHarness.run(strategy, 4, 40, ACCOUNT_ID, AMOUNT)

        assertThat(result.successCount + result.failureCount).isEqualTo(40)
        assertThat(strategy.invocations()).isEqualTo(40)
        assertThat(result.successCount).isEqualTo(40)
    }

    @Test
    @Timeout(30)
    @DisplayName("워커 하나가 터지면 원인 예외를 붙여 실행을 실패시킨다")
    fun failsRunAndKeepsCauseWhenOneWorkerThrows() {
        val lockTimeout = RuntimeException("Lock wait timeout exceeded")
        val strategy = InMemoryStrategy(10_000L, { invocation -> invocation == 1 }, lockTimeout)

        assertThatThrownBy { BenchmarkHarness.run(strategy, 4, 40, ACCOUNT_ID, AMOUNT) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Benchmark worker failed")
            .hasMessageContaining(strategy.name)
            .hasCause(lockTimeout)
    }

    @Test
    @Timeout(30)
    @DisplayName("여러 워커가 터지면 나머지 원인을 suppressed로 보존한다")
    fun suppressesAdditionalCausesWhenManyWorkersThrow() {
        val lockTimeout = RuntimeException("Lock wait timeout exceeded")
        val strategy = InMemoryStrategy(10_000L, { true }, lockTimeout)

        val thrown = catchRun(strategy, 4, 40)

        assertThat(thrown).isInstanceOf(IllegalStateException::class.java)
        assertThat(thrown?.cause).isSameAs(lockTimeout)
        assertThat(thrown?.suppressed).hasSize(3)
        assertThat(thrown?.suppressed).allSatisfy { assertThat(it).isSameAs(lockTimeout) }
    }

    @Test
    @Timeout(30)
    @DisplayName("워커가 터지면 통계를 반환하지 않는다")
    fun neverReportsStatisticsFromPartialRun() {
        val boom = RuntimeException("boom")
        val strategy = InMemoryStrategy(10_000L, { invocation -> invocation == 1 }, boom)

        val thrown = catchRun(strategy, 4, 40)

        assertThat(thrown).isNotNull()
    }

    private fun catchRun(strategy: DeductStrategy, concurrency: Int, totalRequests: Int): Throwable? =
        try {
            BenchmarkHarness.run(strategy, concurrency, totalRequests, ACCOUNT_ID, AMOUNT)
            null
        } catch (t: Throwable) {
            t
        }

    private class InMemoryStrategy(
        private val initialBalance: Long,
        private val failWhen: (Int) -> Boolean = { false },
        private val failure: RuntimeException? = null
    ) : DeductStrategy {

        private val balances = ConcurrentHashMap<Long, AtomicLong>()
        private val invocations = AtomicInteger()

        override val name: String = "in-memory-fake"

        override fun deduct(accountId: Long, amount: Long): DeductStrategy.DeductOutcome {
            val invocation = invocations.incrementAndGet()
            if (failure != null && failWhen(invocation)) {
                throw failure
            }
            val balance = balances.computeIfAbsent(accountId) { AtomicLong(initialBalance) }
            while (true) {
                val current = balance.get()
                if (current < amount) {
                    return DeductStrategy.DeductOutcome(false, 0)
                }
                if (balance.compareAndSet(current, current - amount)) {
                    return DeductStrategy.DeductOutcome(true, 0)
                }
            }
        }

        fun invocations(): Int = invocations.get()
    }

    companion object {
        private const val ACCOUNT_ID = 1L
        private const val AMOUNT = 100L
    }
}
