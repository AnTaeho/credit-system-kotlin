package com.example.credit_system_kotlin.heartbeat

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app.heartbeat")
data class HeartbeatProperties(
    val timeoutSeconds: Long,
    val refreshIntervalSeconds: Long
)
