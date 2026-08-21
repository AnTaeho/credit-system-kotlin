package com.example.credit_system_kotlin.global.config

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor

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
}
