package com.example.credit_system_kotlin.job.concurrency

import com.example.credit_system_kotlin.CreditSystemKotlinApplication
import com.example.credit_system_kotlin.global.event.WorkerDrainCompleted
import com.example.credit_system_kotlin.global.event.WorkerDrainStarted
import com.example.credit_system_kotlin.heartbeat.HeartbeatRegistry
import com.example.credit_system_kotlin.heartbeat.HeartbeatState
import com.example.credit_system_kotlin.job.domain.JobStatus
import com.example.credit_system_kotlin.job.generation.stub.GenerationStubClient
import com.example.credit_system_kotlin.job.repository.JobRepository
import com.example.credit_system_kotlin.job.service.HoldService
import com.example.credit_system_kotlin.user.domain.User
import com.example.credit_system_kotlin.user.repository.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.config.BeanDefinitionCustomizer
import org.springframework.boot.SpringApplication
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.event.EventListener
import org.springframework.context.support.GenericApplicationContext
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
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
            val user = context.getBean(UserRepository::class.java).save(User("acme", 1000L))

            val running = holdService.requestGeneration(user.persistedId, "drain-1", "a cat").jobId
            val waiting = holdService.requestGeneration(user.persistedId, "drain-2", "a dog").jobId
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
            assertHeartbeatStaysAliveDuringDrain(heartbeatRegistry, probe, running)

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
     * 상한을 넘긴 경우. 스텁을 hang 모드로 두면 job 은 **어떤 상한 안에도** 끝나지 않으므로,
     * 드레인은 기다리기를 포기하는 것 말고 할 수 있는 일이 없다. 그때 포기한 수가 이벤트와
     * 로그에 드러나야 한다 — 드러나지 않으면 "배포가 job 을 회수에 떠넘겼다"는 사실이
     * 아무 데도 남지 않는다.
     */
    @Test
    fun `드레인 상한을 넘긴 job 은 포기한 수가 이벤트에 드러나고 PROCESSING 으로 남는다`() {
        val context = bootApplication(*HANG_OVERRIDES)
        var closed = false
        // 매달린 워커 스레드는 non-daemon 이라 풀어 주지 않으면 테스트 JVM 이 끝나지 않는다.
        val stubClient = context.getBean(GenerationStubClient::class.java)
        val workerExecutor = context.getBean("generationWorkerExecutor", ThreadPoolTaskExecutor::class.java)
        try {
            val probe = context.getBean(DrainProbe::class.java)
            val holdService = context.getBean(HoldService::class.java)
            val jobRepository = context.getBean(JobRepository::class.java)
            val user = context.getBean(UserRepository::class.java).save(User("acme", 1000L))

            val hanging = holdService.requestGeneration(user.persistedId, "hang-1", "a cat").jobId
            probe.watchedJobIds = listOf(hanging)

            await().atMost(20, TimeUnit.SECONDS).untilAsserted {
                assertThat(jobRepository.findById(hanging).orElseThrow().status).isEqualTo(JobStatus.PROCESSING)
            }

            val closing = thread(name = "sigterm-hang") { context.close() }
            closing.join(CLOSE_TIMEOUT.toMillis())
            assertThat(closing.isAlive)
                .describedAs("상한이 있으니 닫기는 job 이 안 끝나도 돌아와야 한다")
                .isFalse()
            closed = true

            val completed = requireNotNull(probe.completed.get())
            assertThat(completed.abandoned)
                .describedAs("상한 안에 못 끝낸 job 수가 이벤트에 드러나야 한다")
                .isEqualTo(1)
            assertThat(completed.drained).isZero()
            assertThat(probe.statusesAfterDrain.get()[hanging])
                .describedAs("포기한 job 은 PROCESSING 으로 남아 회수 대상이 된다")
                .isEqualTo(JobStatus.PROCESSING)
        } finally {
            stubClient.releaseHang()
            workerExecutor.threadPoolExecutor.shutdownNow()
            if (!closed) {
                context.close()
            }
        }
    }

    /**
     * heartbeat 타임아웃(2초)보다 긴 창([WATCH_WINDOW]) 동안 계속 살아 있는지 본다. 갱신이
     * 멈췄다면 창이 끝나기 전에 만료된다.
     *
     * 창이 끝나기 전에 드레인이 먼저 끝나면 읽기를 멈춘다. 드레인이 반환한 다음에는 빈 소멸이
     * 시작돼 `LettuceConnectionFactory` 가 destroy 되고, 그 뒤의 조회는 heartbeat 가 죽어서가
     * 아니라 **읽을 수단이 사라져서** UNKNOWN 이 된다 — 드레인과 무관한 실패다. 대신 실제로
     * 지켜본 시간이 heartbeat 타임아웃보다 짧으면 아무것도 증명하지 못했으므로 그때도 실패한다.
     */
    private fun assertHeartbeatStaysAliveDuringDrain(
        registry: HeartbeatRegistry,
        probe: DrainProbe,
        jobId: Long
    ) {
        val startedAt = System.nanoTime()
        val deadline = startedAt + WATCH_WINDOW.toNanos()
        while (System.nanoTime() < deadline && probe.completed.get() == null) {
            assertThat(registry.heartbeatState(jobId, 0))
                .describedAs("드레인 중에 heartbeat 가 끊기면 다른 인스턴스가 살아 있는 job 을 회수해 간다")
                .isEqualTo(HeartbeatState.LIVE)
            Thread.sleep(POLL_MILLIS)
        }
        assertThat(Duration.ofNanos(System.nanoTime() - startedAt))
            .describedAs("heartbeat 타임아웃보다 짧게 지켜봤다면 '갱신되고 있다'를 본 것이 아니다")
            .isGreaterThanOrEqualTo(HEARTBEAT_TIMEOUT)
    }

    /** 애플리케이션의 실제 빈 구성은 그대로 두고 관찰용 빈 하나만 얹는다. */
    private class ProbeInitializer : ApplicationContextInitializer<GenericApplicationContext> {
        override fun initialize(applicationContext: GenericApplicationContext) {
            // 오버로드가 둘이라(생성자 인자 / 커스터마이저) 빈 배열의 타입을 명시해 골라 준다.
            applicationContext.registerBean(DrainProbe::class.java, *emptyArray<BeanDefinitionCustomizer>())
        }
    }

    private fun bootApplication(vararg overrides: String): ConfigurableApplicationContext {
        val application = SpringApplication(CreditSystemKotlinApplication::class.java)
        application.addInitializers(ProbeInitializer())
        return application.run(*(BASE_ARGS + overrides))
    }

    companion object {
        private val WATCH_WINDOW: Duration = Duration.ofSeconds(3)

        /** `app.heartbeat.timeout-seconds` 로 그대로 내려가는 값. 관찰 창이 이보다 짧으면 증명이 안 된다 */
        private val HEARTBEAT_TIMEOUT: Duration = Duration.ofSeconds(2)
        private val CLOSE_TIMEOUT: Duration = Duration.ofSeconds(60)
        private const val POLL_MILLIS = 200L

        /**
         * hang 모드 변형의 덮어쓰기.
         *
         * 드레인 상한은 `app.processing.timeout-seconds` 에서 유도되므로 그 값을 2초로 줄여
         * 상한 초과를 몇 초 만에 재현한다. `absolute-timeout-seconds` 를 같이 내리는 이유는
         * 값 자체가 필요해서가 아니라 `AppProperties.Processing` 이 "절대 상한 > 후보 기준"을
         * 강제하기 때문이다 — 안 내리면 부팅에서 죽는다.
         *
         * `spring.lifecycle.timeout-per-shutdown-phase` 는 application.yml 에서 같은
         * 프로퍼티를 읽으므로 그대로 두면 드레인 상한과 **정확히 같아져** 둘이 동시에 만료된다.
         * 여기서 보려는 것은 드레인의 포기이지 스프링의 phase 타임아웃이 아니라서, 그 경주를
         * 없애려고 phase 상한만 넉넉히 떼어 둔다.
         */
        private val HANG_OVERRIDES: Array<String> = arrayOf(
            "--app.stub.hang=true",
            "--app.processing.timeout-seconds=2",
            "--app.processing.absolute-timeout-seconds=3",
            "--spring.lifecycle.timeout-per-shutdown-phase=30s"
        )

        private val BASE_ARGS: Array<String> = buildList {
            add("--spring.profiles.active=test")
            add("--spring.main.banner-mode=off")
            // 웹서버를 띄운다. 보관본(step8)은 `web-application-type=none` 이었지만 step9-B 의
            // SecurityConfig 가 `HttpSecurity` 를 받는 이상 서블릿 없이는 컨텍스트가 뜨지 않는다.
            // 띄우는 편이 실물에 더 가깝기도 하다 — 드레인은 웹서버가 멈춘 **뒤** phase 에서
            // 도는데, 웹서버가 없으면 그 순서 자체가 테스트에 등장하지 않는다.
            // 포트는 0(임의)이다. 이 테스트가 도는 동안 8080 을 쓰는 다른 것이 있을 수 있다.
            add("--server.port=0")
            add("--management.server.port=0")
            add("--app.scheduling.enabled=true")
            add("--app.worker.enabled=true")
            add("--app.stub.failure-rate=0.0")
            // 한 번에 하나만 처리하게 해서, 두 번째 job 이 드레인 중에 선점되는지를 볼 수 있게 한다.
            add("--app.worker.concurrency=1")
            add("--app.worker.batch-size=1")
            add("--app.scheduling.worker-interval-millis=100")
            // 드레인 창 안에서 heartbeat 갱신이 여러 번 일어나야 하도록 짧게 잡는다.
            // 값을 두 번 적지 않는다 — 관찰 창의 하한도 같은 상수에서 나온다.
            add("--app.heartbeat.timeout-seconds=${HEARTBEAT_TIMEOUT.toSeconds()}")
            add("--app.heartbeat.refresh-interval-seconds=1")
            // 처리 시간이 heartbeat 타임아웃보다 충분히 길어야 "갱신되고 있다"를 관찰할 수 있다.
            // 드레인이 시작되기 전에 웹서버의 graceful shutdown 이 먼저 몇 초를 쓰므로, 그만큼을
            // 빼고도 관찰 창(3초)이 드레인 안에 통째로 들어가도록 넉넉히 잡는다.
            // app.generation.timeout-seconds(20) 보다는 작아야 한다 — 넘으면 스텁이 끊는다.
            add("--app.stub.min-delay-millis=12000")
            add("--app.stub.max-delay-millis=12000")
            for ((key, value) in SharedContainers.propertiesFor("drain_shutdown_e2e")) {
                add("--$key=$value")
            }
        }.toTypedArray()
    }
}
