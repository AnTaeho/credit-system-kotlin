package com.example.credit_system_kotlin.global.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app")
data class AppProperties(
    val generation: Generation,
    val stub: Stub,
    val processing: Processing,
    val idempotency: Idempotency
) {

    data class Generation(val cost: Long, val maxAttempts: Int)

    data class Stub(val failureRate: Double, val minDelayMillis: Long, val maxDelayMillis: Long)

    data class Processing(val timeoutSeconds: Long)

    data class Idempotency(val retentionDays: Long) {
        init {
            require(retentionDays >= 1) { "idempotency retention-days는 1 이상이어야 합니다." }
        }
    }
}
