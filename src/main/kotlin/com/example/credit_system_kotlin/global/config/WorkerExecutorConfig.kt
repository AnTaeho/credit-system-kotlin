package com.example.credit_system_kotlin.global.config

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor

@Configuration
@EnableConfigurationProperties(WorkerProperties::class)
class WorkerExecutorConfig {

    @Bean("generationWorkerExecutor")
    fun generationWorkerExecutor(workerProperties: WorkerProperties): ThreadPoolTaskExecutor {
        val executor = ThreadPoolTaskExecutor()
        executor.corePoolSize = workerProperties.concurrency
        executor.maxPoolSize = workerProperties.concurrency
        executor.queueCapacity = 0
        executor.setThreadNamePrefix("generation-worker-")
        executor.initialize()
        return executor
    }
}