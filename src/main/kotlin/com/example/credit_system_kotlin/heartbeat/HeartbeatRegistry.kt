package com.example.credit_system_kotlin.heartbeat

import com.example.credit_system_kotlin.global.config.WorkerProperties
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

private val log = LoggerFactory.getLogger(HeartbeatRegistry::class.java)

/**
 * heartbeat 조회의 세 가지 결과.
 *
 * `Boolean` 이 아니라 세 값인 이유는 "heartbeat 가 없다"와 "heartbeat 저장소를 못 봤다"가
 * 전혀 다른 사실이기 때문이다. 둘을 `false` 하나로 뭉치면 Redis 장애가 heartbeat 부재로
 * 둔갑하고, 예외로 뭉치면 Redis 장애가 회수 자체를 막는다. 판단은 호출자가 한다.
 */
enum class HeartbeatState {
    /** 만료되지 않은 heartbeat 가 있다. 워커가 살아 있다는 뜻이다 */
    LIVE,

    /** 조회에 성공했고, heartbeat 가 없거나 이미 만료됐다 */
    ABSENT,

    /** heartbeat 저장소에 닿지 못했다. 살아 있는지 아닌지 알 수 없다 */
    UNKNOWN
}

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

    /**
     * Redis 조회 실패를 예외로 던지지 않고 [HeartbeatState.UNKNOWN] 으로 돌려준다.
     * "Redis 가 안 보인다"는 사실을 호출자가 판단 재료로 쓸 수 있어야 하기 때문이다 —
     * 예외로 던지면 호출자의 catch 가 회수 로직 전체를 통째로 건너뛴다.
     */
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

    /**
     * 회수가 끝난 뒤의 뒷정리라 실패해도 상태는 이미 바뀌어 있다. 그래서 예외를 삼킨다.
     * 지우지 못하고 남은 ZSET 엔트리는 나중에 [findExpiredAttempts] 가 다시 집어 오지만,
     * 그때 `failIfProcessing` 이 0행을 돌려주므로 아무 일도 일어나지 않고 조용히 소거된다.
     */
    fun removeHeartbeat(jobId: Long, attemptNo: Int) {
        val member = JobAttempt(jobId, attemptNo).toMember()
        try {
            redisTemplate.opsForZSet().remove(KEY, member)
        } catch (e: RuntimeException) {
            log.warn("heartbeat 제거 실패, 다음 만료 스캔이 소거한다: jobId={}, attemptNo={}", jobId, attemptNo, e)
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
