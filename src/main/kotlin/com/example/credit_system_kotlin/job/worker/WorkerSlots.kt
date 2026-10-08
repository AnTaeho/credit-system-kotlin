package com.example.credit_system_kotlin.job.worker

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor

fun interface WorkerSlots {
    fun free(): Int
}

@Configuration
class WorkerSlotsConfig {

    @Bean
    fun workerSlots(
        @Qualifier("generationWorkerExecutor") executor: ThreadPoolTaskExecutor
    ): WorkerSlots = WorkerSlots { executor.maxPoolSize - executor.activeCount }
}
