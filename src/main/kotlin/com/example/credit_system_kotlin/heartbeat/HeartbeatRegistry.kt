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

/** heartbeat 가 없는 것과 저장소를 못 본 것을 따로 둔다. 하나로 뭉치면 Redis 장애가 heartbeat 부재로 읽힌다. */
enum class HeartbeatState {
    /** 만료되지 않은 heartbeat 가 있다. 워커가 살아 있다는 뜻이다 */
    LIVE,

    /** 조회에 성공했고, heartbeat 가 없거나 이미 만료됐다 */
    ABSENT,

    /** heartbeat 저장소에 닿지 못했다. 살아 있는지 아닌지 알 수 없다 */
    UNKNOWN
}

/** 처리 중인 시도의 heartbeat 를 Redis ZSET 하나에 둔다. score 가 만료 시각(epoch 초)이다. */
@Component
class HeartbeatRegistry(
    private val redisTemplate: StringRedisTemplate,
    private val heartbeatProperties: HeartbeatProperties,
    @Qualifier("heartbeatScheduler") private val scheduler: TaskScheduler
) {

    /** 첫 갱신은 여기서 바로 하고, 주기 갱신은 한 주기 뒤부터 돈다. */
    fun startHeartbeat(jobId: Long, attemptNo: Int): ScheduledFuture<*> {
        val attempt = JobAttempt(jobId, attemptNo)
        refreshHeartbeat(attempt)
        val interval = Duration.ofSeconds(heartbeatProperties.refreshIntervalSeconds)
        return scheduler.scheduleAtFixedRate({ refreshHeartbeat(attempt) }, Instant.now().plus(interval), interval)
    }

    /** 워커가 처리를 끝낼 때 부른다. 돌고 있는 갱신은 끊지 않고 다음 주기만 취소한 뒤 엔트리를 지운다. */
    fun stopHeartbeat(jobId: Long, attemptNo: Int, future: ScheduledFuture<*>) {
        future.cancel(false)
        removeHeartbeat(jobId, attemptNo)
    }

    /** 만료 시각이 지난 시도를 모은다. 형식이 깨진 멤버는 그 자리에서 지우고 결과에서 뺀다. */
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

    /** 남겨 두면 스캔마다 다시 걸리므로 지우고 경고만 남긴다. */
    private fun removeUnparseableMember(member: String) {
        redisTemplate.opsForZSet().remove(KEY, member)
        log.warn("해석할 수 없는 heartbeat 멤버 제거: {}", member)
    }

    /** 만료 시각을 지금 + timeout 으로 다시 쓴다. Redis 가 안 보이면 로그만 남기고 다음 주기에 맡긴다. */
    private fun refreshHeartbeat(attempt: JobAttempt) {
        val expireAt = Instant.now().epochSecond + heartbeatProperties.timeoutSeconds
        try {
            redisTemplate.opsForZSet().add(KEY, attempt.toMember(), expireAt.toDouble())
        } catch (e: RuntimeException) {
            log.warn("heartbeat 갱신 실패: jobId={}, attemptNo={}", attempt.jobId, attempt.attemptNo, e)
        }
    }

    /** Redis 조회가 실패하면 [HeartbeatState.UNKNOWN] 을 돌려준다. 예외로 던지면 호출자의 catch 가 회수를 통째로 건너뛴다. */
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
     * 실패해도 예외를 삼킨다. 남은 엔트리는 다음 만료 스캔이 다시 집지만
     * `failIfProcessing` 이 0행이라 아무 일 없이 지워진다.
     */
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
