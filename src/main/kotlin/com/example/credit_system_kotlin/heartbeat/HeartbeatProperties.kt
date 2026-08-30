package com.example.credit_system_kotlin.heartbeat

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app.heartbeat")
data class HeartbeatProperties(
    val timeoutSeconds: Long,
    val refreshIntervalSeconds: Long
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
                "그렇지 않으면 갱신 주기가 돌아오기 전에 heartbeat가 만료되어 살아있는 job이 회수됩니다: " +
                "refresh-interval-seconds=$refreshIntervalSeconds, timeout-seconds=$timeoutSeconds"
        }
    }
}
