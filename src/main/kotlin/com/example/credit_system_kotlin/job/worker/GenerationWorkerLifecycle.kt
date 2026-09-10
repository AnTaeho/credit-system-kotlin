package com.example.credit_system_kotlin.job.worker

import com.example.credit_system_kotlin.global.config.AppProperties
import com.example.credit_system_kotlin.global.event.WorkerDrainCompleted
import com.example.credit_system_kotlin.global.event.WorkerDrainStarted
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.SmartLifecycle
import org.springframework.scheduling.concurrent.ExecutorConfigurationSupport
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.util.concurrent.TimeUnit

private val log = LoggerFactory.getLogger(GenerationWorkerLifecycle::class.java)

/**
 * SIGTERM 이 왔을 때 진행 중인 job 을 **회수가 아니라 완료로** 끝낸다.
 *
 * 회수([com.example.credit_system_kotlin.job.scheduling.DeadJobRecoveryTask])는 안전망이다.
 * 인스턴스가 예고 없이 죽었을 때 돈이 영영 묶이지 않게 하는 장치이지, 배포 때마다 타라고
 * 만든 경로가 아니다. 배포는 예고된 종료이므로 그 대가(재시도 한 번, 회수 지연,
 * step10 에서 두 대가 되면 살아 있는 job 을 다른 인스턴스가 뺏는 것)를 치를 이유가 없다.
 *
 * ## 종료 순서
 *
 * Spring 의 종료는 `ContextClosedEvent` → `SmartLifecycle.stop`(phase 내림차순) →
 * 빈 소멸(`@PreDestroy`) 순서다. 이 클래스가 [PHASE] 에 앉으면 그 사이 어디에 끼는지가 정해진다.
 *
 * 1. `ContextClosedEvent` — `@Scheduled` 를 굴리는 `taskScheduler`([ExecutorConfigurationSupport]
 *    의 자식)가 여기서 이미 `shutdown()` 된다. 주기 task 는 더 이상 재예약되지 않는다.
 *    다만 *지금 돌고 있는* 디스패치 주기를 기다려 주지는 않아서, 그 창은 [WorkerDrainGate] 가 막는다.
 * 2. `SmartLifecycle.stop` — 내장 웹서버가 먼저 멈추고(phase 2147482623/2147481599)
 *    그다음 이 클래스가 [PHASE] 에서 드레인한다. HTTP 유입이 끊긴 뒤에 드레인이 시작되므로
 *    드레인 도중에 새 job 이 생기지 않는다.
 * 3. 빈 소멸 — `HeartbeatRegistry.shutdown()` 이 여기서야 불린다. 즉 **드레인이 도는 동안
 *    heartbeat 는 살아서 갱신된다.** [stop] 이 스스로 블록하고, 빈 소멸은 모든 lifecycle
 *    stop 이 끝난 뒤에 시작되기 때문이다. 이 순서가 깨지면 살아 있는 job 의 heartbeat 가
 *    먼저 끊겨 다른 인스턴스가 그 job 을 회수해 간다.
 *
 * ## phase 를 고른 근거
 *
 * stop 은 phase 내림차순이므로 "나중에 멈춘다 = phase 가 작다"이다. 드레인은 웹서버보다도,
 * 스케줄러/executor 자신의 lifecycle 보다도 나중이어야 하므로 그중 가장 작은
 * [ExecutorConfigurationSupport.DEFAULT_PHASE](1073741823) 보다 1 작은 값을 쓴다.
 * 임의의 숫자를 적지 않고 상수에서 유도해, 스프링이 값을 바꾸면 같이 따라가게 했다.
 */
@Component
class GenerationWorkerLifecycle(
    @Qualifier("generationWorkerExecutor") private val workerExecutor: ThreadPoolTaskExecutor,
    private val drainGate: WorkerDrainGate,
    private val eventPublisher: ApplicationEventPublisher,
    private val clock: Clock,
    appProperties: AppProperties
) : SmartLifecycle {

    /**
     * 드레인을 기다려 주는 상한.
     *
     * `app.processing.timeout-seconds` 를 그대로 쓴다. 그 시간을 넘긴 PROCESSING job 은
     * 어차피 정체 회수의 대상이므로, 더 기다려도 얻는 것이 없다. 상한을 별도 설정으로
     * 새로 만들지 않은 것도 같은 이유다 — 두 값이 어긋날 수 있는 자리를 만들지 않는다.
     */
    private val drainTimeout: Duration = Duration.ofSeconds(appProperties.processing.timeoutSeconds)

    @Volatile
    private var running = false

    override fun start() {
        running = true
    }

    override fun isRunning(): Boolean = running

    /**
     * 문을 닫고, 풀을 내리고, 남은 job 이 스스로 끝나기를 기다린다.
     *
     * 대기를 `ThreadPoolTaskExecutor.setAwaitTerminationSeconds` 에 맡기지 않고 직접 한다.
     * 그쪽은 다 끝났는지 여부를 로그로만 흘리고 호출자에게 돌려주지 않아서, "몇 개를 포기했나"를
     * 이벤트로 드러낼 수가 없다. 대신 `waitForTasksToCompleteOnShutdown` 은 켜 둔다
     * ([WorkerExecutorConfig] 참고) — 그게 꺼져 있으면 `shutdownNow()` 가 생성 중인
     * 스레드를 인터럽트해 버려서 드레인이라는 말 자체가 성립하지 않는다.
     */
    override fun stop() {
        running = false
        val startedAt = clock.instant()
        drainGate.close()

        val inFlight = workerExecutor.activeCount
        log.info("워커 드레인 시작: 진행 중 job={}, 상한={}초", inFlight, drainTimeout.toSeconds())
        eventPublisher.publishEvent(WorkerDrainStarted(inFlight))

        workerExecutor.initiateShutdown()
        val terminated = awaitTermination()
        val abandoned = if (terminated) 0 else workerExecutor.activeCount
        val duration = Duration.between(startedAt, clock.instant())

        if (abandoned > 0) {
            log.warn(
                "워커 드레인 상한 초과: 포기한 job={}, 상한={}초. 이 job 들은 PROCESSING 으로 남아 회수 대상이 된다",
                abandoned, drainTimeout.toSeconds()
            )
        } else {
            log.info("워커 드레인 완료: 끝낸 job={}, 걸린 시간={}ms", inFlight, duration.toMillis())
        }
        eventPublisher.publishEvent(WorkerDrainCompleted(inFlight, abandoned, duration))
    }

    private fun awaitTermination(): Boolean =
        try {
            workerExecutor.threadPoolExecutor.awaitTermination(drainTimeout.toMillis(), TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            log.warn("워커 드레인 대기가 인터럽트됐다. 남은 job 은 회수에 맡긴다", e)
            false
        }

    override fun getPhase(): Int = PHASE

    companion object {
        /** 스케줄러와 워커 풀 자신의 lifecycle 보다 **나중에** 멈추기 위한 값. 클래스 주석 참고 */
        val PHASE: Int = ExecutorConfigurationSupport.DEFAULT_PHASE - 1
    }
}
