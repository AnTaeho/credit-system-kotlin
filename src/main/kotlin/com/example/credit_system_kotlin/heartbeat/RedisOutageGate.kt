package com.example.credit_system_kotlin.heartbeat

import com.example.credit_system_kotlin.global.config.AppProperties
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

private val log = LoggerFactory.getLogger(RedisOutageGate::class.java)

class RedisOutageGate internal constructor(
    private val appProperties: AppProperties,
    private val clock: Clock
) {

    private val lastRedisFailureAt = AtomicReference(NONE)
    private val suppressionStartedAt = AtomicReference(NONE)
    private val lastSuppressionAlertAt = AtomicReference(NONE)

    fun recordFailure() {
        val now = clock.instant()
        if (!isInGraceAt(now)) {
            suppressionStartedAt.set(now)
            lastSuppressionAlertAt.set(now)
        }
        lastRedisFailureAt.set(now)
    }

    fun isInRecoveryGrace(): Boolean {
        val now = clock.instant()
        if (isInGraceAt(now)) {
            alertIfSuppressionProlonged(now)
            return true
        }
        clearSuppression(now)
        return false
    }

    private fun isInGraceAt(now: Instant): Boolean =
        now.isBefore(lastRedisFailureAt.get().plusSeconds(appProperties.heartbeat.timeoutSeconds))

    private fun alertIfSuppressionProlonged(now: Instant) {
        val alertSeconds = appProperties.heartbeat.suppressionAlertSeconds
        val startedAt = suppressionStartedAt.get()
        val lastAlertAt = lastSuppressionAlertAt.get()
        if (startedAt == NONE || now.isBefore(lastAlertAt.plusSeconds(alertSeconds))) {
            return
        }
        if (!lastSuppressionAlertAt.compareAndSet(lastAlertAt, now)) {
            return
        }
        log.error(
            "Redis 장애가 {}초째 지속 중입니다. PROCESSING job 회수가 그동안 계속 억제되고 있습니다. " +
                "Redis가 복구될 때까지 만료 job은 FAILED로 전이되지 않고 재시도·환불도 지연됩니다.",
            Duration.between(startedAt, now).seconds
        )
    }

    private fun clearSuppression(now: Instant) {
        val startedAt = suppressionStartedAt.getAndSet(NONE)
        if (startedAt == NONE) {
            return
        }
        lastSuppressionAlertAt.set(NONE)
        log.info(
            "Redis 복구 유예 종료, PROCESSING job 회수를 재개합니다: 억제 지속 {}초",
            Duration.between(startedAt, now).seconds
        )
    }

    companion object {
        private val NONE: Instant = Instant.EPOCH
    }
}
