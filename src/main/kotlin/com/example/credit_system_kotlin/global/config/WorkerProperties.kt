package com.example.credit_system_kotlin.global.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app.worker")
data class WorkerProperties(
    val enabled: Boolean,
    val batchSize: Int
)
