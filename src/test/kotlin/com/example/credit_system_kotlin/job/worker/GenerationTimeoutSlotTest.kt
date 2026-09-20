package com.example.credit_system_kotlin.job.worker

import com.example.credit_system_kotlin.global.config.AppProperties
import com.example.credit_system_kotlin.global.config.WorkerProperties
import com.example.credit_system_kotlin.global.config.appProperties
import com.example.credit_system_kotlin.heartbeat.HeartbeatRegistry
import com.example.credit_system_kotlin.job.domain.Job
import com.example.credit_system_kotlin.job.generation.stub.GenerationStubClient
import com.example.credit_system_kotlin.job.service.JobLifecycleService
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.timeout
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.test.util.ReflectionTestUtils
import java.time.Duration
import java.util.concurrent.ScheduledFuture

/**
 * 타임아웃의 값어치는 "돈이 안 묶인다"와 "워커 자리가 돌아온다" 둘이다. 앞은 job 상태가,
 * 뒤는 여기가 못 박는다. 타임아웃으로 끊었는데 스레드가 그대로 묶여 있으면 풀이 말라붙어
 * 뒤따르는 job 이 전부 HOLDING 에서 늙는다 — 이번 조각을 한 의미가 없어진다.
 *
 * 실제 풀 + 실제 프로세서 + 실제 스텁으로 돌린다. `executor.execute { stub.generate() }` 만으로는
 * "풀이 예외를 삼키더라"밖에 증명하지 못한다.
 */
@ExtendWith(MockitoExtension::class)
class GenerationTimeoutSlotTest {

    @Mock lateinit var heartbeatRegistry: HeartbeatRegistry

    @Mock lateinit var jobLifecycleService: JobLifecycleService

    @Mock lateinit var heartbeatFuture: ScheduledFuture<*>

    @Test
    fun `타임아웃으로 끝난 뒤 워커 슬롯이 회복된다`() {
        val stub = stubClient(AppProperties.Stub(0.0, 5_000, 5_000))
        runOnRealPool(stub) { executor, slots, processor, job ->
            executor.execute { processor.runGeneration(job) }

            await().atMost(Duration.ofSeconds(5)).untilAsserted {
                assertThat(slots.free()).isZero()
            }

            verify(jobLifecycleService, timeout(TIMEOUT_VERIFY_MILLIS)).markFailed(1L, 0)
            await().atMost(Duration.ofSeconds(5)).untilAsserted {
                assertThat(slots.free()).isEqualTo(CONCURRENCY)
            }
        }
    }

    @Test
    fun `hang 모드에서는 상한이 지나도 슬롯이 회복되지 않는다`() {
        val stub = stubClient(AppProperties.Stub(0.0, 0, 0, hang = true))
        runOnRealPool(stub) { executor, slots, processor, job ->
            try {
                executor.execute { processor.runGeneration(job) }

                await().atMost(Duration.ofSeconds(5)).untilAsserted {
                    assertThat(slots.free()).isZero()
                }
                // 상한(1초)의 두 배가 지나도 자리가 돌아오지 않는다. 클라이언트가 제 타임아웃을
                // 지키지 못하면 바깥쪽 절대 상한 없이는 슬롯이 영영 묶인다는 증언이다.
                await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(4)).untilAsserted {
                    assertThat(slots.free()).isZero()
                }
            } finally {
                // 테스트가 영원히 매달리지 않도록 반드시 깨운다.
                stub.releaseHang()
            }

            await().atMost(Duration.ofSeconds(5)).untilAsserted {
                assertThat(slots.free()).isEqualTo(CONCURRENCY)
            }
        }
    }

    private fun stubClient(stub: AppProperties.Stub) = GenerationStubClient(
        appProperties(
            generation = AppProperties.Generation(cost = 100L, maxAttempts = 3, timeoutSeconds = 1),
            stub = stub
        )
    )

    private fun runOnRealPool(
        stub: GenerationStubClient,
        body: (ThreadPoolTaskExecutor, WorkerSlots, GenerationJobProcessor, Job) -> Unit
    ) {
        val config = WorkerExecutorConfig()
        val executor = config.generationWorkerExecutor(WorkerProperties(true, 3, CONCURRENCY))
        val slots = config.workerSlots(executor)
        val processor = GenerationJobProcessor(heartbeatRegistry, stub, jobLifecycleService)
        val job = Job.hold(10L, 100L, "cat").also { ReflectionTestUtils.setField(it, "id", 1L) }
        doReturn(heartbeatFuture).whenever(heartbeatRegistry).startHeartbeat(1L, 0)

        try {
            body(executor, slots, processor, job)
        } finally {
            executor.shutdown()
        }
    }

    companion object {
        private const val CONCURRENCY = 1
        private const val TIMEOUT_VERIFY_MILLIS = 5_000L
    }
}
