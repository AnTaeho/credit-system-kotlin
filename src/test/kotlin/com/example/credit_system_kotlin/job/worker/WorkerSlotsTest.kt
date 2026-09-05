package com.example.credit_system_kotlin.job.worker

import com.example.credit_system_kotlin.global.config.WorkerProperties
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * `workerSlots` 빈이 실제 [ThreadPoolTaskExecutor] 위에서 맞는 값을 내는지 본다.
 *
 * `maxPoolSize - activeCount` 라는 식 자체는 한 줄이지만, 그 한 줄이 틀리면 디스패처가
 * 아무것도 넘기지 못하거나(항상 0) 예전처럼 헛선점을 반복한다(항상 양수). 실물 풀로 확인한다.
 */
class WorkerSlotsTest {

    @Test
    fun `풀이 꽉 차면 0을 돌려주고 task가 끝나면 다시 채워진다`() {
        val config = WorkerExecutorConfig()
        val executor = config.generationWorkerExecutor(WorkerProperties(true, 3, CONCURRENCY))
        val slots = config.workerSlots(executor)

        assertThat(slots.free()).isEqualTo(CONCURRENCY)

        val release = CountDownLatch(1)
        val started = CountDownLatch(CONCURRENCY)
        try {
            repeat(CONCURRENCY) {
                executor.execute {
                    started.countDown()
                    release.await(10, TimeUnit.SECONDS)
                }
            }
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue()

            await().atMost(Duration.ofSeconds(5)).untilAsserted {
                assertThat(slots.free()).isZero()
            }

            release.countDown()

            await().atMost(Duration.ofSeconds(5)).untilAsserted {
                assertThat(slots.free()).isEqualTo(CONCURRENCY)
            }
        } finally {
            release.countDown()
            executor.shutdown()
        }
    }

    companion object {
        private const val CONCURRENCY = 2
    }
}
