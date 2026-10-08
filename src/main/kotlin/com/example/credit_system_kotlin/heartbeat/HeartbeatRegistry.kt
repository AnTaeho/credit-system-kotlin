package com.example.credit_system_kotlin.heartbeat

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.scheduling.TaskScheduler
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ScheduledFuture

private val log = LoggerFactory.getLogger(HeartbeatRegistry::class.java)

enum class HeartbeatState {
    LIVE,
    ABSENT,
    UNKNOWN
}

@Component
class HeartbeatRegistry(
    private val redisTemplate: StringRedisTemplate,
    private val heartbeatProperties: HeartbeatProperties,
    @Qualifier("heartbeatScheduler") private val scheduler: TaskScheduler
) {

    fun startHeartbeat(jobId: Long, attemptNo: Int): ScheduledFuture<*> {
        val attempt = JobAttempt(jobId, attemptNo)
        refreshHeartbeat(attempt)
        val interval = Duration.ofSeconds(heartbeatProperties.refreshIntervalSeconds)
        return scheduler.scheduleAtFixedRate({ refreshHeartbeat(attempt) }, Instant.now().plus(interval), interval)
    }

    fun stopHeartbeat(jobId: Long, attemptNo: Int, future: ScheduledFuture<*>) {
        future.cancel(false)
        removeHeartbeat(jobId, attemptNo)
    }

    fun findExpiredAttempts(): Set<JobAttempt> {
        val now = Instant.now().epochSecond.toDouble()
        val expired = redisTemplate.opsForZSet().rangeByScore(KEY, Double.NEGATIVE_INFINITY, now)
        if (expired.isNullOrEmpty()) {
            return emptySet()
        }
        val attempts = mutableSetOf<JobAttempt>()
        for (member in expired) {
            val attempt = JobAttempt.parse(member)
            if (attempt != null) {
                attempts.add(attempt)
            } else {
                removeUnparseableMember(member)
            }
        }
        return attempts
    }

    private fun removeUnparseableMember(member: String) {
        redisTemplate.opsForZSet().remove(KEY, member)
        log.warn("해석할 수 없는 heartbeat 멤버 제거: {}", member)
    }

    private fun refreshHeartbeat(attempt: JobAttempt) {
        val expireAt = Instant.now().epochSecond + heartbeatProperties.timeoutSeconds
        try {
            redisTemplate.opsForZSet().add(KEY, attempt.toMember(), expireAt.toDouble())
        } catch (e: RuntimeException) {
            log.warn("heartbeat 갱신 실패: jobId={}, attemptNo={}", attempt.jobId, attempt.attemptNo, e)
        }
    }

    fun heartbeatState(jobId: Long, attemptNo: Int): HeartbeatState {
        val member = JobAttempt(jobId, attemptNo).toMember()
        val score = try {
            redisTemplate.opsForZSet().score(KEY, member)
        } catch (e: RuntimeException) {
            log.warn("heartbeat 조회 실패, UNKNOWN 으로 처리: jobId={}, attemptNo={}", jobId, attemptNo, e)
            return HeartbeatState.UNKNOWN
        }
        return if (score != null && score > Instant.now().epochSecond) {
            HeartbeatState.LIVE
        } else {
            HeartbeatState.ABSENT
        }
    }

    fun removeHeartbeat(jobId: Long, attemptNo: Int) {
        val member = JobAttempt(jobId, attemptNo).toMember()
        try {
            redisTemplate.opsForZSet().remove(KEY, member)
        } catch (e: RuntimeException) {
            log.warn("heartbeat 제거 실패, 다음 만료 스캔이 소거한다: jobId={}, attemptNo={}", jobId, attemptNo, e)
        }
    }

    companion object {
        private const val KEY = "heartbeats"
    }
}
