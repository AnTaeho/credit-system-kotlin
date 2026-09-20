package com.example.credit_system_kotlin.global.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app")
data class AppProperties(
    val generation: Generation,
    val stub: Stub,
    val processing: Processing,
    val idempotency: Idempotency,
    val admin: Admin = Admin()
) {

    data class Generation(val cost: Long, val maxAttempts: Int)

    data class Stub(val failureRate: Double, val minDelayMillis: Long, val maxDelayMillis: Long)

    data class Processing(val timeoutSeconds: Long)

    data class Idempotency(val retentionDays: Long) {
        init {
            require(retentionDays >= 1) { "idempotency retention-days는 1 이상이어야 합니다." }
        }
    }

    /** 운영자 지급 1회 상한. 결제 없이 돈을 만드는 경로라 한 번에 만들 수 있는 양을 묶어 둔다. */
    data class Admin(val maxGrantAmount: Long = DEFAULT_MAX_GRANT_AMOUNT) {
        init {
            require(maxGrantAmount >= 1) { "admin max-grant-amount는 1 이상이어야 합니다." }
        }
    }

    companion object {
        const val DEFAULT_MAX_GRANT_AMOUNT = 1_000_000L
    }
}
