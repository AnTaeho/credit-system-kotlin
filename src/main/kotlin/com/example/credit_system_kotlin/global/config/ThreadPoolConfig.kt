package com.example.credit_system_kotlin.global.config

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler

/**
 * 이 앱이 쓰는 스레드 풀 셋을 한곳에서 만든다.
 *
 * 풀이 여러 파일에 흩어져 있으면 "지금 스레드가 몇 개까지 뜨는가"를 답하려고 코드를 뒤져야 하고,
 * 이름 없는 풀(`pool-N-thread-M`)은 스레드 덤프에서 누구 것인지 알아볼 수 없다. 그래서 만드는 자리를
 * 여기로 모으고 풀마다 접두사를 붙였다.
 *
 * | 빈 | 하는 일 | 크기 | 스레드 이름 |
 * |---|---|---|---|
 * | [taskScheduler] | `@Scheduled` 메서드 | `spring.task.scheduling.pool.size` | `scheduling-` |
 * | [generationWorkerExecutor] | 선점한 job 의 생성 호출 | `app.worker.concurrency`, 대기열 없음 | `generation-worker-` |
 * | [heartbeatScheduler] | 처리 중인 job 의 heartbeat 갱신 | `app.worker.concurrency` | `heartbeat-` |
 *
 * `@Scheduled` 메서드는 디스패처, 대사, 죽은 job 스캔, 스냅샷, 정리 cron 둘이다.
 *
 * 종료할 때 남은 작업을 기다리는 설정(`waitForTasksToCompleteOnShutdown` 등)은 어느 풀에도 두지 않는다.
 * 종료 시 드레인은 이 저장소에서 하지 않기로 했다.
 */
@Configuration
@EnableConfigurationProperties(WorkerProperties::class)
class ThreadPoolConfig {

    /**
     * `@Scheduled` 가 도는 풀. 부트가 자동으로 만들던 것과 같은 빈을 직접 선언한다.
     *
     * 직접 선언하는 이유는 [heartbeatScheduler] 때문이다. 부트의 `TaskSchedulingAutoConfiguration` 은
     * `TaskScheduler` 빈이 하나라도 있으면 자기 `taskScheduler` 를 만들지 않고 물러난다. heartbeat
     * 스케줄러만 등록해 두면 컨텍스트에 남는 `TaskScheduler` 가 그것 하나뿐이라 `@Scheduled` 메서드가
     * 전부 heartbeat 풀 위에서 돌고, 디스패처와 정리 작업이 heartbeat 갱신과 스레드를 다투게 된다.
     *
     * 빈 이름은 반드시 `taskScheduler` 여야 한다. `TaskScheduler` 가 둘 이상이면 스프링은 이 이름의
     * 빈을 `@Scheduled` 용으로 고른다.
     *
     * 부트의 빌더로 만들기 때문에 `spring.task.scheduling.*` 설정(풀 크기, 접두사)이 그대로 먹는다.
     */
    @Bean("taskScheduler")
    fun taskScheduler(builder: ThreadPoolTaskSchedulerBuilder): ThreadPoolTaskScheduler = builder.build()

    /**
     * 선점한 job 의 생성 호출이 도는 풀.
     *
     * `corePoolSize == maxPoolSize` 로 스레드 수를 고정하고 대기열을 두지 않는다. 동시에 처리할 만큼만
     * 받고, 넘치는 job 은 풀 안에 쌓아 두지 않고 다음 디스패치 주기로 미루기 위해서다.
     */
    @Bean("generationWorkerExecutor")
    fun generationWorkerExecutor(workerProperties: WorkerProperties): ThreadPoolTaskExecutor =
        ThreadPoolTaskExecutor().apply {
            corePoolSize = workerProperties.concurrency
            maxPoolSize = workerProperties.concurrency
            queueCapacity = 0
            setThreadNamePrefix("generation-worker-")
            initialize()
        }

    /**
     * 처리 중인 job 의 heartbeat 를 주기적으로 갱신하는 풀.
     *
     * 크기를 워커 동시 실행 수에 맞춘다. 동시에 도는 job 수가 곧 주기 작업 수이기 때문이다. 스레드가
     * 그보다 적으면 한 job 의 갱신이 Redis 지연으로 막혔을 때 다른 job 의 갱신이 그 뒤에 줄을 서고,
     * 멀쩡히 처리 중인 job 의 heartbeat 가 만료돼 죽은 것으로 회수된다.
     *
     * `removeOnCancelPolicy` 를 켜서 job 이 끝나 취소된 주기 작업을 큐에서 바로 뺀다. 꺼 두면 취소된
     * 작업이 다음 발화 시각까지 큐에 남는다.
     */
    @Bean("heartbeatScheduler")
    fun heartbeatScheduler(workerProperties: WorkerProperties): ThreadPoolTaskScheduler =
        ThreadPoolTaskScheduler().apply {
            poolSize = workerProperties.concurrency
            setThreadNamePrefix("heartbeat-")
            isRemoveOnCancelPolicy = true
        }
}
