package com.example.credit_system_kotlin.heartbeat

import java.time.Clock
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

class RedisOutageGate internal constructor(
    private val heartbeatProperties: HeartbeatProperties,
    private val clock: Clock
) {

    private val lastRedisFailureAt = AtomicReference(NONE)

    fun recordFailure() {
        lastRedisFailureAt.set(clock.instant())
    }

    /** 마지막 Redis 실패로부터 timeout 이 지나기 전에는 살아있는 job 을 회수하지 않는다. */
    fun isInRecoveryGrace(): Boolean =
        clock.instant().isBefore(lastRedisFailureAt.get().plusSeconds(heartbeatProperties.timeoutSeconds))

    companion object {
        private val NONE: Instant = Instant.EPOCH
    }
}
