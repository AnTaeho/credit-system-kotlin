package com.example.credit_system_kotlin.global.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app.idempotency")
data class IdempotencyProperties(
    val retentionDays: Long
) {
    init {
        require(retentionDays >= 1) { "idempotency retention-days는 1 이상이어야 합니다." }
    }
}
