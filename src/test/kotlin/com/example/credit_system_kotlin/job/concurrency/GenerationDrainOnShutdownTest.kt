package com.example.credit_system_kotlin.job.concurrency

import com.example.credit_system_kotlin.CreditSystemKotlinApplication
import com.example.credit_system_kotlin.global.event.WorkerDrainCompleted
import com.example.credit_system_kotlin.global.event.WorkerDrainStarted
import com.example.credit_system_kotlin.heartbeat.HeartbeatRegistry
import com.example.credit_system_kotlin.heartbeat.HeartbeatState
import com.example.credit_system_kotlin.job.domain.JobStatus
import com.example.credit_system_kotlin.job.repository.JobRepository
import com.example.credit_system_kotlin.job.service.HoldService
import com.example.credit_system_kotlin.organization.domain.Organization
import com.example.credit_system_kotlin.organization.repository.OrganizationRepository
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.config.BeanDefinitionCustomizer
import org.springframework.boot.SpringApplication
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.event.EventListener
import org.springframework.context.support.GenericApplicationContext
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * SIGTERM 을 흉내 낸다 — 진행 중인 job 이 하나 있는 애플리케이션을 그대로 닫는다.
 *
 * 증명하려는 것은 세 가지고, 셋 다 스프링의 종료 배선에 실제로 올라타야만 성립한다.
 *
 * 1. **완료로 끝난다.** 닫기가 진행 중 job 을 기다렸다가 COMPLETED 로 끝낸다. 회수에
 *    떠넘기지 않는다.
 * 2. **새로 선점하지 않는다.** 대기 중이던 두 번째 job 은 드레인 내내 HOLDING 으로 남는다.
 * 3. **heartbeat 가 드레인 동안 살아서 갱신된다.** heartbeat 타임아웃(2초)보다 긴 창(3초)
 *    동안 계속 살아 있음을 폴링해서 본다. `HeartbeatRegistry` 의 executor 가 드레인보다
 *    먼저 내려갔다면 이 창 안에서 반드시 만료된다. 이게 깨지면 step10 에서 두 대가 됐을 때
 *    다른 인스턴스가 살아 있는 job 을 뺏어 간다.
 *
 * `@SpringBootTest` 를 쓰지 않고 [SpringApplication] 을 직접 띄운다. 스프링 테스트 컨텍스트는
 * 컨텍스트를 캐시해 두고 재사용하는데, 이 테스트는 그 컨텍스트를 **닫는 것 자체가 목적**이라
 * 캐시와 정면으로 충돌한다(닫힌 컨텍스트를 되살리려다 `LifecycleProcessor not initialized`).
 * 직접 띄우면 종료 경로도 실제 애플리케이션의 것 그대로다.
 */
class GenerationDrainOnShutdownTest {

    /**
     * 드레인 도중에만 관찰할 수 있는 것을 붙잡아 둔다. 컨텍스트가 닫히는 중이라 테스트
     * 스레드에서는 이 순간에 빈을 쓸 수 없다.
     */
    class DrainProbe(private val jobRepository: JobRepository) {

        val started = AtomicReference<WorkerDrainStarted?>(null)
        val completed = AtomicReference<WorkerDrainCompleted?>(null)

        /** 드레인이 끝난 직후, 아직 빈이 살아 있을 때 읽은 job 상태. 닫힌 뒤에는 못 읽는다. */
        val statusesAfterDrain = AtomicReference<Map<Long, JobStatus>>(emptyMap())

        var watchedJobIds: List<Long> = emptyList()

        @EventListener
        fun onStarted(event: WorkerDrainStarted) {
            started.set(event)
        }

        @EventListener
        fun onCompleted(event: WorkerDrainCompleted) {
            completed.set(event)
            statusesAfterDrain.set(
                watchedJobIds.associateWith { jobRepository.findById(it).orElseThrow().status }
            )
        }
    }

    @Test
    fun `종료 중에도 진행 중 job 은 완료로 끝나고 새 선점은 없다`() {
        val context = bootApplication()
        var closed = false
        try {
            val probe = context.getBean(DrainProbe::class.java)
            val holdService = context.getBean(HoldService::class.java)
            val jobRepository = context.getBean(JobRepository::class.java)
            val heartbeatRegistry = context.getBean(HeartbeatRegistry::class.java)
            val organization = context.getBean(OrganizationRepository::class.java)
                .save(Organization("acme", 1000L))

            val running = holdService.requestGeneration(organization.persistedId, "drain-1", "a cat").jobId
            val waiting = holdService.requestGeneration(organization.persistedId, "drain-2", "a dog").jobId
            probe.watchedJobIds = listOf(running, waiting)

            // 전제는 "PROCESSING 이면서 heartbeat 가 살아 있다"이다. 선점(DB 상태 전이)은 디스패처
            // 스레드에서, 첫 heartbeat 기록은 그 뒤 워커 스레드가 runGeneration 에 들어가서야
            // 일어난다. 그 사이 heartbeat 는 ABSENT 라 상태만 보고 닫으면 드레인과 무관하게
            // 이 창을 잡는다(CI 러너에서 실제로 잡혔다). 운영에서는 updatedAt 백스톱이 이 창을 덮는다.
            await().atMost(20, TimeUnit.SECONDS).untilAsserted {
                assertThat(jobRepository.findById(running).orElseThrow().status).isEqualTo(JobStatus.PROCESSING)
                assertThat(heartbeatRegistry.heartbeatState(running, 0)).isEqualTo(HeartbeatState.LIVE)
            }

            val closing = thread(name = "sigterm") { context.close() }
            await().atMost(10, TimeUnit.SECONDS).until { probe.started.get() != null }
            assertHeartbeatStaysAliveDuringDrain(heartbeatRegistry, running)

            closing.join(CLOSE_TIMEOUT.toMillis())
            assertThat(closing.isAlive).describedAs("닫기가 상한 안에 끝나야 한다").isFalse()
            closed = true

            assertThat(probe.started.get()?.inFlight)
                .describedAs("드레인이 시작될 때 job 하나가 실행 중이어야 한다")
                .isEqualTo(1)

            val completed = requireNotNull(probe.completed.get())
            assertThat(completed.abandoned).isZero()
            assertThat(completed.drained).isEqualTo(1)

            val statuses = probe.statusesAfterDrain.get()
            assertThat(statuses[running])
                .describedAs("진행 중이던 job 은 회수가 아니라 완료로 끝나야 한다")
                .isEqualTo(JobStatus.COMPLETED)
            assertThat(statuses[waiting])
                .describedAs("드레인 중에는 새 job 을 선점하지 않는다")
                .isEqualTo(JobStatus.HOLDING)
        } finally {
            if (!closed) {
                context.close()
            }
        }
    }

    /**
     * heartbeat 타임아웃(2초)보다 긴 창 동안 계속 살아 있는지 본다. 갱신이 멈췄다면 창이 끝나기
     * 전에 만료된다.
     */
    private fun assertHeartbeatStaysAliveDuringDrain(registry: HeartbeatRegistry, jobId: Long) {
        val deadline = System.nanoTime() + WATCH_WINDOW.toNanos()
        while (System.nanoTime() < deadline) {
            assertThat(registry.heartbeatState(jobId, 0))
                .describedAs("드레인 중에 heartbeat 가 끊기면 다른 인스턴스가 살아 있는 job 을 회수해 간다")
                .isEqualTo(HeartbeatState.LIVE)
            Thread.sleep(POLL_MILLIS)
        }
    }

    /** 애플리케이션의 실제 빈 구성은 그대로 두고 관찰용 빈 하나만 얹는다. */
    private class ProbeInitializer : ApplicationContextInitializer<GenericApplicationContext> {
        override fun initialize(applicationContext: GenericApplicationContext) {
            // 오버로드가 둘이라(생성자 인자 / 커스터마이저) 빈 배열의 타입을 명시해 골라 준다.
            applicationContext.registerBean(DrainProbe::class.java, *emptyArray<BeanDefinitionCustomizer>())
        }
    }

    private fun bootApplication(): ConfigurableApplicationContext {
        val application = SpringApplication(CreditSystemKotlinApplication::class.java)
        application.addInitializers(ProbeInitializer())
        return application.run(*ARGS)
    }

    companion object {
        private val WATCH_WINDOW: Duration = Duration.ofSeconds(3)
        private val CLOSE_TIMEOUT: Duration = Duration.ofSeconds(60)
        private const val POLL_MILLIS = 200L

        private val ARGS: Array<String> = buildList {
            add("--spring.profiles.active=test")
            add("--spring.main.banner-mode=off")
            // 이 테스트가 보는 것은 워커 드레인이다. 웹서버는 띄우지 않는다.
            add("--spring.main.web-application-type=none")
            add("--app.scheduling.enabled=true")
            add("--app.worker.enabled=true")
            add("--app.stub.failure-rate=0.0")
            // 한 번에 하나만 처리하게 해서, 두 번째 job 이 드레인 중에 선점되는지를 볼 수 있게 한다.
            add("--app.worker.concurrency=1")
            add("--app.worker.batch-size=1")
            add("--app.scheduling.worker-interval-millis=100")
            // 드레인 창 안에서 heartbeat 갱신이 여러 번 일어나야 하도록 짧게 잡는다.
            add("--app.heartbeat.timeout-seconds=2")
            add("--app.heartbeat.refresh-interval-seconds=1")
            // 처리 시간이 heartbeat 타임아웃보다 충분히 길어야 "갱신되고 있다"를 관찰할 수 있다.
            add("--app.stub.min-delay-millis=6000")
            add("--app.stub.max-delay-millis=6000")
            for ((key, value) in SharedContainers.propertiesFor("drain_shutdown_e2e")) {
                add("--$key=$value")
            }
        }.toTypedArray()
    }
}
