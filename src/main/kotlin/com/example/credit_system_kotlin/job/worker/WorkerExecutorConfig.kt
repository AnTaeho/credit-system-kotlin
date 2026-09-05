package com.example.credit_system_kotlin.job.worker

import com.example.credit_system_kotlin.global.config.WorkerProperties
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor

/**
 * 워커 풀에 지금 몇 자리가 비어 있는지 알려준다.
 *
 * 디스패처가 "선점부터 하고 executor 가 받아 주길 기대"하는 대신 "받아 줄 만큼만 선점"하도록
 * 만들기 위해 존재한다. 풀 구현을 [GenerationWorker] 에 노출하지 않으려고 좁은 인터페이스로 끊었다.
 */
fun interface WorkerSlots {
    /** 지금 비어 있는 실행 슬롯 수. 음수가 나올 수 있으므로 호출자가 0 과 비교한다 */
    fun free(): Int
}

@Configuration
@EnableConfigurationProperties(WorkerProperties::class)
class WorkerExecutorConfig {

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
     * `maxPoolSize - activeCount`. `activeCount` 는 지금 task 를 실행 중인 스레드 수다.
     *
     * 이 값은 **과소평가일 수는 있어도 과대평가일 수 없다.** 이 풀에 task 를 넣는 스레드는
     * `@Scheduled` 디스패처 하나뿐이고, 다른 스레드는 task 를 끝내면서 `activeCount` 를
     * 줄이기만 하기 때문이다. 자세한 근거는 [GenerationWorker.dispatchPendingJobs] 주석에 있다.
     */
    @Bean
    fun workerSlots(
        @Qualifier("generationWorkerExecutor") executor: ThreadPoolTaskExecutor
    ): WorkerSlots = WorkerSlots { executor.maxPoolSize - executor.activeCount }
}
