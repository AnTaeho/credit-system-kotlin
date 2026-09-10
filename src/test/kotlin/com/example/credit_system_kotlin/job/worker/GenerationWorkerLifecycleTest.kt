package com.example.credit_system_kotlin.job.worker

import com.example.credit_system_kotlin.global.config.AppProperties
import com.example.credit_system_kotlin.global.config.WorkerProperties
import com.example.credit_system_kotlin.global.config.appProperties
import com.example.credit_system_kotlin.global.event.WorkerDrainCompleted
import com.example.credit_system_kotlin.global.event.WorkerDrainStarted
import com.example.credit_system_kotlin.support.RecordingEventPublisher
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.scheduling.concurrent.ExecutorConfigurationSupport
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.time.Clock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 드레인이 "진행 중 job 을 끝까지 기다린다"를 실물 풀 위에서 확인한다.
 *
 * 컨텍스트를 띄우지 않는 이유는 여기서 검증할 것이 스프링의 종료 배선이 아니라 [stop] 안의
 * 순서 — 문 닫기 → 풀 내리기 → 대기 → 이벤트 — 이기 때문이다. 종료 배선이 실제로 이 순서를
 * 태우는지는 `GenerationDrainOnShutdownTest` 가 컨테이너 위에서 본다.
 */
class GenerationWorkerLifecycleTest {

    @Test
    fun `진행 중 job 이 끝날 때까지 stop 이 돌아오지 않는다`() {
        val fixture = Fixture()
        val finished = AtomicBoolean(false)
        val started = CountDownLatch(1)

        try {
            fixture.executor.execute {
                started.countDown()
                Thread.sleep(TASK_MILLIS)
                finished.set(true)
            }
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue()

            fixture.lifecycle.stop()

            assertThat(finished)
                .describedAs("stop 이 돌아왔는데 job 이 아직 안 끝났다면 드레인이 아니라 그냥 종료다")
                .isTrue()
        } finally {
            fixture.executor.shutdown()
        }
    }

    @Test
    fun `드레인이 끝나면 끝낸 job 수와 걸린 시간을 이벤트로 알린다`() {
        val fixture = Fixture()
        val started = CountDownLatch(2)

        try {
            repeat(2) {
                fixture.executor.execute {
                    started.countDown()
                    Thread.sleep(TASK_MILLIS)
                }
            }
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue()

            fixture.lifecycle.stop()

            val drainStarted = fixture.publisher.events.filterIsInstance<WorkerDrainStarted>().single()
            assertThat(drainStarted.inFlight).isEqualTo(2)

            val completed = fixture.publisher.events.filterIsInstance<WorkerDrainCompleted>().single()
            assertThat(completed.inFlightAtStart).isEqualTo(2)
            assertThat(completed.abandoned).isZero()
            assertThat(completed.drained).isEqualTo(2)
            assertThat(completed.duration).isPositive()
        } finally {
            fixture.executor.shutdown()
        }
    }

    @Test
    fun `상한을 넘긴 job 은 포기하고 그 수를 이벤트로 드러낸다`() {
        // 상한 1초, job 은 10초짜리다. 기다려 주기를 포기하는 것 말고 다른 결말이 없다.
        val fixture = Fixture(processingTimeoutSeconds = 1)
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)

        try {
            fixture.executor.execute {
                started.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue()

            fixture.lifecycle.stop()

            val completed = fixture.publisher.events.filterIsInstance<WorkerDrainCompleted>().single()
            assertThat(completed.abandoned)
                .describedAs("상한 안에 못 끝낸 job 은 PROCESSING 으로 남아 회수 대상이 된다")
                .isEqualTo(1)
            assertThat(completed.drained).isZero()
        } finally {
            release.countDown()
            fixture.executor.shutdown()
        }
    }

    @Test
    fun `드레인이 시작되면 디스패처의 문이 닫힌다`() {
        val fixture = Fixture()
        val dispatched = AtomicBoolean(false)

        assertThat(fixture.gate.isOpen).isTrue()

        fixture.lifecycle.stop()

        fixture.gate.runIfOpen { dispatched.set(true) }
        assertThat(dispatched)
            .describedAs("드레인 중에 선점하면 넘길 곳이 없어 롤백이 된다")
            .isFalse()
        assertThat(fixture.gate.isOpen).isFalse()
    }

    @Test
    fun `phase 는 스케줄러와 워커 풀보다 나중에 멈추는 값이다`() {
        // stop 은 phase 내림차순이므로 "나중에 멈춘다 = phase 가 작다".
        assertThat(GenerationWorkerLifecycle.PHASE)
            .describedAs("이 값이 커지면 스케줄러가 아직 살아 있는 채로 드레인이 시작된다")
            .isLessThan(ExecutorConfigurationSupport.DEFAULT_PHASE)
        assertThat(GenerationWorkerLifecycle.PHASE)
            .describedAs("웹서버가 새 요청을 끊기 전에 드레인이 시작되면 안 된다")
            .isLessThan(WEB_SERVER_GRACEFUL_SHUTDOWN_PHASE)
    }

    private class Fixture(processingTimeoutSeconds: Long = 30) {
        val publisher = RecordingEventPublisher()
        val gate = WorkerDrainGate()
        val executor: ThreadPoolTaskExecutor =
            WorkerExecutorConfig().generationWorkerExecutor(WorkerProperties(true, 3, CONCURRENCY))
        val lifecycle = GenerationWorkerLifecycle(
            executor, gate, publisher, Clock.systemUTC(),
            appProperties(processing = AppProperties.Processing(processingTimeoutSeconds))
        )
    }

    companion object {
        private const val CONCURRENCY = 3
        private const val TASK_MILLIS = 300L

        /** `WebServerGracefulShutdownLifecycle.SMART_LIFECYCLE_PHASE`. 웹서버 모듈에 의존하지 않으려고 값만 적는다 */
        private const val WEB_SERVER_GRACEFUL_SHUTDOWN_PHASE = Int.MAX_VALUE - 1024
    }
}
