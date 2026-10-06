package com.example.credit_system_kotlin.global.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** `taskScheduler` 빈이 빠지면 기동은 되는데 `@Scheduled` 가 heartbeat 풀에서 돈다. 그걸 잡는다. */
class ThreadPoolConfigTest {

    @Test
    fun `heartbeat 스레드 풀은 워커 동시 실행 수만큼 만들어진다`() {
        val scheduler = ThreadPoolConfig().heartbeatScheduler(WorkerProperties(true, 20, 3))
        scheduler.initialize()

        try {
            assertThat(scheduler.scheduledThreadPoolExecutor.corePoolSize).isEqualTo(3)
            assertThat(scheduler.threadNamePrefix).isEqualTo("heartbeat-")
        } finally {
            scheduler.shutdown()
        }
    }

    @Test
    fun `Scheduled 풀과 heartbeat 풀은 서로 다른 빈이고 Scheduled 풀은 부트 설정을 따른다`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration::class.java))
            .withUserConfiguration(SchedulingEnabled::class.java, ThreadPoolConfig::class.java)
            .withPropertyValues(
                "spring.task.scheduling.pool.size=4",
                "app.worker.enabled=true",
                "app.worker.batch-size=20",
                "app.worker.concurrency=3"
            )
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context.getBeansOfType(TaskScheduler::class.java).keys)
                    .containsExactlyInAnyOrder("taskScheduler", "heartbeatScheduler")

                val taskScheduler = context.getBean("taskScheduler", ThreadPoolTaskScheduler::class.java)
                val heartbeatScheduler = context.getBean("heartbeatScheduler", ThreadPoolTaskScheduler::class.java)

                assertThat(taskScheduler).isNotSameAs(heartbeatScheduler)
                assertThat(taskScheduler.scheduledThreadPoolExecutor.corePoolSize).isEqualTo(4)
                assertThat(taskScheduler.threadNamePrefix).isEqualTo("scheduling-")
                assertThat(heartbeatScheduler.scheduledThreadPoolExecutor.corePoolSize).isEqualTo(3)
                assertThat(heartbeatScheduler.threadNamePrefix).isEqualTo("heartbeat-")
            }
    }

    @Test
    fun `Scheduled 메서드는 scheduling 풀의 스레드에서 돈다`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration::class.java))
            .withUserConfiguration(SchedulingEnabled::class.java, ThreadPoolConfig::class.java)
            .withBean(ThreadNameRecorder::class.java)
            .withPropertyValues(
                "spring.task.scheduling.pool.size=4",
                "app.worker.enabled=true",
                "app.worker.batch-size=20",
                "app.worker.concurrency=3"
            )
            .run { context ->
                assertThat(context).hasNotFailed()

                val threadName = context.getBean(ThreadNameRecorder::class.java)
                    .firstThreadName.get(AWAIT_SECONDS, TimeUnit.SECONDS)

                assertThat(threadName).startsWith("scheduling-")
            }
    }

    /** 처음 돈 스레드 이름을 남긴다. */
    open class ThreadNameRecorder {
        val firstThreadName = CompletableFuture<String>()

        @Scheduled(fixedDelay = 50)
        open fun record() {
            firstThreadName.complete(Thread.currentThread().name)
        }
    }

    /** 부트의 스케줄러 자동 설정은 `@EnableScheduling` 이 있어야 켜진다. */
    @Configuration
    @EnableScheduling
    class SchedulingEnabled
    companion object {
        private const val AWAIT_SECONDS = 10L
    }
}
