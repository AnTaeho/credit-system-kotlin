package com.example.credit_system_kotlin.global.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app")
data class AppProperties(
    val generation: Generation,
    val stub: Stub,
    val heartbeat: Heartbeat,
    val processing: Processing
) {

    data class Generation(val cost: Long, val maxAttempts: Int)

    data class Stub(val failureRate: Double, val minDelayMillis: Long, val maxDelayMillis: Long)

    data class Heartbeat(
        val timeoutSeconds: Long,
        val refreshIntervalSeconds: Long,
        val suppressionAlertSeconds: Long
    ) {
        init {
            require(timeoutSeconds >= 1) {
                "heartbeat timeout-seconds는 1 이상이어야 합니다: $timeoutSeconds"
            }
            require(refreshIntervalSeconds >= 1) {
                "heartbeat refresh-interval-seconds는 1 이상이어야 합니다: $refreshIntervalSeconds"
            }
            require(refreshIntervalSeconds < timeoutSeconds) {
                "heartbeat refresh-interval-seconds는 timeout-seconds보다 작아야 합니다. " +
                        "그렇지 않으면 복구 유예 동안 살아있는 워커가 heartbeat를 갱신하지 못해 정상 job이 회수됩니다: " +
                        "refresh-interval-seconds=$refreshIntervalSeconds, timeout-seconds=$timeoutSeconds"
            }
            require(suppressionAlertSeconds >= 1) {
                "heartbeat suppression-alert-seconds는 1 이상이어야 합니다: $suppressionAlertSeconds"
            }
        }
    }

    data class Processing(val timeoutSeconds: Long)
}
