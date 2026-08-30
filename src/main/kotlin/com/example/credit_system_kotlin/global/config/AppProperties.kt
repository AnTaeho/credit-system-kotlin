package com.example.credit_system_kotlin.global.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app")
data class AppProperties(
    val generation: Generation,
    val stub: Stub,
    val processing: Processing
) {

    data class Generation(val cost: Long, val maxAttempts: Int)

    data class Stub(val failureRate: Double, val minDelayMillis: Long, val maxDelayMillis: Long)

    data class Processing(val timeoutSeconds: Long)
}
