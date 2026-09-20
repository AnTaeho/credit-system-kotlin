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

    /**
     * [timeoutSeconds] 는 외부 생성 한 번을 기다려 주는 상한이다. 이 상한을 지키는 주체는
     * 호출자가 아니라 생성 클라이언트 구현이다. 스텁은 스스로 이 값에서 끊고, 진짜 Claude
     * 구현에서는 같은 값이 SDK 클라이언트의 타임아웃 설정으로 넘어간다.
     */
    data class Generation(
        val cost: Long,
        val maxAttempts: Int,
        val timeoutSeconds: Long = DEFAULT_GENERATION_TIMEOUT_SECONDS
    ) {
        init {
            require(timeoutSeconds >= 1) { "generation timeout-seconds는 1 이상이어야 합니다." }
        }

        fun timeoutMillis(): Long = timeoutSeconds * MILLIS_PER_SECOND
    }

    /** [hang] 은 타임아웃조차 먹지 않는 "응답 없음"을 재현하는 장애 주입 손잡이다. 기본은 꺼짐. */
    data class Stub(
        val failureRate: Double,
        val minDelayMillis: Long,
        val maxDelayMillis: Long,
        val hang: Boolean = false
    )

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
        const val DEFAULT_GENERATION_TIMEOUT_SECONDS = 20L
        private const val MILLIS_PER_SECOND = 1_000L
    }
}
