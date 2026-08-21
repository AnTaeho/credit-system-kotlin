package com.example.credit_system_kotlin.scheduler

import com.example.credit_system_kotlin.global.config.AppProperties
import com.example.credit_system_kotlin.global.config.WorkerProperties
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

private val log = LoggerFactory.getLogger(HeartbeatRegistry::class.java)

@Component
class HeartbeatRegistry internal constructor(
    private val redisTemplate: StringRedisTemplate,
    private val appProperties: AppProperties,
    workerProperties: WorkerProperties,
    private val clock: Clock
) {

    @Autowired
    constructor(
        redisTemplate: StringRedisTemplate,
        appProperties: AppProperties,
        workerProperties: WorkerProperties
    ) : this(redisTemplate, appProperties, workerProperties, Clock.systemUTC())

    private val executor: ScheduledExecutorService =
        Executors.newScheduledThreadPool(workerProperties.concurrency)

    private val outageGate = RedisOutageGate(appProperties, clock)

    fun startHeartbeat(jobId: Long, attemptNo: Int): ScheduledFuture<*> {
        val attempt = JobAttempt(jobId, attemptNo)
        refreshHeartbeat(attempt)
        val interval = appProperties.heartbeat.refreshIntervalSeconds
        return executor.scheduleAtFixedRate({ refreshHeartbeat(attempt) }, interval, interval, TimeUnit.SECONDS)
    }

    fun stopHeartbeat(jobId: Long, attemptNo: Int, future: ScheduledFuture<*>) {
        future.cancel(false)
        removeHeartbeat(jobId, attemptNo)
    }

    fun findExpiredAttempts(): Set<JobAttempt> {
        if (outageGate.isInRecoveryGrace()) {
            log.debug("Redis 복구 유예 구간, heartbeat 만료 판정 보류")
            return emptySet()
        }
        val now = clock.instant().epochSecond.toDouble()
        val expired = try {
            redisTemplate.opsForZSet().rangeByScore(KEY, Double.NEGATIVE_INFINITY, now)
        } catch (e: RuntimeException) {
            outageGate.recordFailure()
            log.warn("heartbeat 만료 조회 실패, 이번 주기는 건너뜀", e)
            return emptySet()
        }
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
        try {
            redisTemplate.opsForZSet().remove(KEY, member)
            log.warn("해석할 수 없는 heartbeat 멤버 제거: {}", member)
        } catch (e: RuntimeException) {
            outageGate.recordFailure()
            log.warn("해석할 수 없는 heartbeat 멤버 제거 실패: {}", member, e)
        }
    }

    private fun refreshHeartbeat(attempt: JobAttempt) {
        val expireAt = clock.instant().epochSecond + appProperties.heartbeat.timeoutSeconds
        try {
            redisTemplate.opsForZSet().add(KEY, attempt.toMember(), expireAt.toDouble())
        } catch (e: RuntimeException) {
            outageGate.recordFailure()
            log.warn("heartbeat 갱신 실패: jobId={}, attemptNo={}", attempt.jobId, attempt.attemptNo, e)
        }
    }

    fun hasLiveHeartbeat(jobId: Long, attemptNo: Int): Boolean {
        if (outageGate.isInRecoveryGrace()) {
            log.debug("Redis 복구 유예 구간, 회수 보류: jobId={}, attemptNo={}", jobId, attemptNo)
            return true
        }
        val member = JobAttempt(jobId, attemptNo).toMember()
        val score = try {
            redisTemplate.opsForZSet().score(KEY, member)
        } catch (e: RuntimeException) {
            outageGate.recordFailure()
            log.warn("heartbeat 조회 실패, 회수 보류: jobId={}, attemptNo={}", jobId, attemptNo, e)
            return true
        }
        return score != null && score > clock.instant().epochSecond
    }

    fun removeHeartbeat(jobId: Long, attemptNo: Int) {
        val member = JobAttempt(jobId, attemptNo).toMember()
        try {
            redisTemplate.opsForZSet().remove(KEY, member)
        } catch (e: RuntimeException) {
            outageGate.recordFailure()
            log.warn("heartbeat 제거 실패: jobId={}, attemptNo={}", jobId, attemptNo, e)
        }
    }

    @PreDestroy
    fun shutdown() {
        executor.shutdownNow()
    }

    companion object {
        private const val KEY = "heartbeats"
    }
}
