package com.example.credit_system_kotlin.global.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app.worker")
data class WorkerProperties(
    val enabled: Boolean,
    val batchSize: Int,
    val concurrency: Int
) {
    init {
        require(batchSize >= 1 && concurrency >= 1) { "worker batch-size와 concurrency는 1 이상이어야 합니다." }
    }
}
