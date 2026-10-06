package com.example.credit_system_kotlin.global.config

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler

/** 스레드 풀은 전부 여기서 만든다. */
@Configuration
@EnableConfigurationProperties(WorkerProperties::class)
class ThreadPoolConfig {

    /**
     * `@Scheduled` 용. [heartbeatScheduler] 가 있으면 부트가 기본 스케줄러를 안 만들어서 직접 둔다.
     * 빈 이름이 `taskScheduler` 여야 `@Scheduled` 가 이 풀을 쓴다.
     */
    @Bean("taskScheduler")
    fun taskScheduler(builder: ThreadPoolTaskSchedulerBuilder): ThreadPoolTaskScheduler = builder.build()

    /** 생성 호출용. 대기열이 없어 넘치는 job 은 다음 디스패치 주기에 다시 잡힌다. */
    @Bean("generationWorkerExecutor")
    fun generationWorkerExecutor(workerProperties: WorkerProperties): ThreadPoolTaskExecutor =
        ThreadPoolTaskExecutor().apply {
            corePoolSize = workerProperties.concurrency
            maxPoolSize = workerProperties.concurrency
            queueCapacity = 0
            setThreadNamePrefix("generation-worker-")
            initialize()
        }

    /** heartbeat 갱신용. job 하나의 갱신이 Redis 에서 막혀도 다른 job 이 밀리지 않게 워커 수만큼 둔다. */
    @Bean("heartbeatScheduler")
    fun heartbeatScheduler(workerProperties: WorkerProperties): ThreadPoolTaskScheduler =
        ThreadPoolTaskScheduler().apply {
            poolSize = workerProperties.concurrency
            setThreadNamePrefix("heartbeat-")
            isRemoveOnCancelPolicy = true
        }
}
