package com.example.credit_system_kotlin.heartbeat

import com.example.credit_system_kotlin.global.config.WorkerProperties
import jakarta.annotation.PreDestroy
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

@Component
class HeartbeatRegistry(
    private val redisTemplate: StringRedisTemplate,
    private val heartbeatProperties: HeartbeatProperties,
    workerProperties: WorkerProperties
) {

    private val executor: ScheduledExecutorService =
        Executors.newScheduledThreadPool(workerProperties.concurrency)

    fun startHeartbeat(jobId: Long, attemptNo: Int): ScheduledFuture<*> {
        val attempt = JobAttempt(jobId, attemptNo)
        refreshHeartbeat(attempt)
        val interval = heartbeatProperties.refreshIntervalSeconds
        return executor.scheduleAtFixedRate({ refreshHeartbeat(attempt) }, interval, interval, TimeUnit.SECONDS)
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
            val attempt = JobAttempt.parse(member) ?: continue
            attempts.add(attempt)
        }
        return attempts
    }

    private fun refreshHeartbeat(attempt: JobAttempt) {
        val expireAt = Instant.now().epochSecond + heartbeatProperties.timeoutSeconds
        redisTemplate.opsForZSet().add(KEY, attempt.toMember(), expireAt.toDouble())
    }

    fun hasLiveHeartbeat(jobId: Long, attemptNo: Int): Boolean {
        val member = JobAttempt(jobId, attemptNo).toMember()
        val score = redisTemplate.opsForZSet().score(KEY, member)
        return score != null && score > Instant.now().epochSecond
    }

    fun removeHeartbeat(jobId: Long, attemptNo: Int) {
        val member = JobAttempt(jobId, attemptNo).toMember()
        redisTemplate.opsForZSet().remove(KEY, member)
    }

    @PreDestroy
    fun shutdown() {
        executor.shutdownNow()
    }

    companion object {
        private const val KEY = "heartbeats"
    }
}
