package com.example.credit_system_kotlin.observability

import com.example.credit_system_kotlin.global.config.WorkerProperties
import com.example.credit_system_kotlin.job.worker.WorkerExecutorConfig
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 슬롯 누수가 **보이는지**를 본다.
 *
 * 절대 상한 회수는 돈만 푼다 — 멈춘 스레드는 돌아오지 않는다. 그 손실은 job 상태 어디에도
 * 안 적히므로 이 게이지가 유일한 증언대다. 실물 풀 위에서 재지 않으면 "게이지를 등록했다"
 * 이상을 증명하지 못하므로 실제 [org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor]
 * 를 쓴다.
 */
class WorkerSlotMetricsTest {

    private val registry = SimpleMeterRegistry()

    private fun freeSlots(): Double =
        registry.get(WorkerSlotMetrics.FREE_SLOTS_METRIC).gauge().value()

    @Test
    fun `슬롯이 묶이면 게이지가 줄고 풀리면 돌아온다`() {
        val config = WorkerExecutorConfig()
        val executor = config.generationWorkerExecutor(WorkerProperties(true, 3, CONCURRENCY))
        WorkerSlotMetrics(registry, config.workerSlots(executor))

        assertThat(freeSlots()).isEqualTo(CONCURRENCY.toDouble())

        val release = CountDownLatch(1)
        val started = CountDownLatch(CONCURRENCY)
        try {
            repeat(CONCURRENCY) {
                executor.execute {
                    started.countDown()
                    release.await(AWAIT_SECONDS, TimeUnit.SECONDS)
                }
            }
            assertThat(started.await(AWAIT_SECONDS, TimeUnit.SECONDS)).isTrue()

            // 여기가 "슬롯이 샜다"의 모습이다. 멈춘 워커라면 이 값이 0 에 붙은 채 돌아오지 않는다.
            await().atMost(Duration.ofSeconds(5)).untilAsserted { assertThat(freeSlots()).isZero() }

            release.countDown()

            await().atMost(Duration.ofSeconds(5)).untilAsserted {
                assertThat(freeSlots()).isEqualTo(CONCURRENCY.toDouble())
            }
        } finally {
            release.countDown()
            executor.shutdown()
        }
    }

    @Test
    fun `게이지에는 태그를 붙이지 않는다`() {
        val config = WorkerExecutorConfig()
        val executor = config.generationWorkerExecutor(WorkerProperties(true, 3, CONCURRENCY))
        try {
            WorkerSlotMetrics(registry, config.workerSlots(executor))

            assertThat(registry.get(WorkerSlotMetrics.FREE_SLOTS_METRIC).gauge().id.tags).isEmpty()
        } finally {
            executor.shutdown()
        }
    }

    companion object {
        private const val CONCURRENCY = 2
        private const val AWAIT_SECONDS = 10L
    }
}
