package com.example.credit_system_kotlin.job.worker

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor

/** 워커 풀의 빈 자리 수. 디스패처가 풀이 받아 줄 만큼만 선점하게 한다. */
fun interface WorkerSlots {
    /** 지금 비어 있는 실행 슬롯 수. 음수가 나올 수 있으므로 호출자가 0 과 비교한다 */
    fun free(): Int
}

/** [WorkerSlots] 를 워커 풀에 묶는다. */
@Configuration
class WorkerSlotsConfig {

    /** 실제보다 적게 나올 수는 있어도 많게 나오지 않는다. 근거는 [GenerationWorker.dispatchPendingJobs] 에 있다. */
    @Bean
    fun workerSlots(
        @Qualifier("generationWorkerExecutor") executor: ThreadPoolTaskExecutor
    ): WorkerSlots = WorkerSlots { executor.maxPoolSize - executor.activeCount }
}
