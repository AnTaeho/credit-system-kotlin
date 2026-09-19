package com.example.credit_system_kotlin.job.ratelimit

import com.example.credit_system_kotlin.global.config.AppProperties
import com.example.credit_system_kotlin.global.event.DefenseOutcome
import com.example.credit_system_kotlin.global.event.DefensePoint
import com.example.credit_system_kotlin.global.event.DefenseTriggered
import com.example.credit_system_kotlin.global.exception.RateLimitedException
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

private val log = LoggerFactory.getLogger(JobCreateRateLimiter::class.java)

/**
 * 사용자별 job 접수 속도 제한. 토큰 버킷이다.
 *
 * 용량은 분당 허용 수와 같고, 토큰은 1분에 걸쳐 균등하게 다시 찬다. 요청 하나가 토큰 하나를 쓴다.
 * 인증이 뚫린 공격자와 우리 자신의 자동화 버그(무한 루프)가 선결제 크레딧을 태우는 속도를 묶는다.
 *
 * 서버가 한 대라 카운터는 앱 메모리에 둔다. 재시작하면 버킷이 가득 찬 상태로 돌아온다.
 * 한 사용자의 갱신은 [ConcurrentHashMap.compute] 안에서 일어나므로 같은 사용자의 동시 요청이
 * 토큰 하나를 둘이 나눠 쓰는 일은 없다.
 */
@Component
class JobCreateRateLimiter(
    appProperties: AppProperties,
    private val clock: Clock,
    private val eventPublisher: ApplicationEventPublisher
) {

    private val settings = appProperties.rateLimit.jobCreate

    // 토큰을 정수로 센다. 토큰 1개 = [MILLIS_PER_MINUTE] 단위, 1ms 마다 perMinute 단위가 찬다.
    // 소수로 세면 "정확히 6초 뒤 1개" 같은 경계에서 0.9999… 가 나와 거절이 한 번 더 붙는다.
    private val refillPerMilli = settings.perMinute.toLong()
    private val capacity = refillPerMilli * UNITS_PER_TOKEN

    private val buckets = ConcurrentHashMap<Long, Bucket>()

    /** 통과하면 토큰 하나를 쓰고 돌아온다. 버킷이 비었으면 [RateLimitedException] 을 던진다. */
    fun acquire(userId: Long) {
        if (!settings.enabled) return

        val now = clock.millis()
        evictIdleBucketsIfTooMany(now)

        var decision: Decision = Decision.Passed
        buckets.compute(userId) { _, current ->
            val refilled = current?.refilledAt(now) ?: Bucket(capacity, now, rejecting = false)
            if (refilled.units >= UNITS_PER_TOKEN) {
                decision = Decision.Passed
                refilled.copy(units = refilled.units - UNITS_PER_TOKEN, rejecting = false)
            } else {
                decision = Decision.Rejected(retryAfterSeconds(refilled.units), firstInStreak = !refilled.rejecting)
                refilled.copy(rejecting = true)
            }
        }

        when (val result = decision) {
            Decision.Passed ->
                eventPublisher.publishEvent(DefenseTriggered(DefensePoint.RATE_LIMIT, DefenseOutcome.APPLIED))

            is Decision.Rejected -> {
                eventPublisher.publishEvent(DefenseTriggered(DefensePoint.RATE_LIMIT, DefenseOutcome.REJECTED))
                logRejection(userId, result.firstInStreak)
                throw RateLimitedException(result.retryAfterSeconds)
            }
        }
    }

    private fun Bucket.refilledAt(now: Long): Bucket {
        val elapsed = (now - lastRefillMillis).coerceIn(0, MILLIS_PER_MINUTE) // 1분이면 이미 가득 찬다
        return copy(units = min(capacity, units + elapsed * refillPerMilli), lastRefillMillis = now)
    }

    /** 다음 토큰 하나가 찰 때까지 남은 초. 올림하고, 0초라고 답하지는 않는다. */
    private fun retryAfterSeconds(units: Long): Long {
        val millis = ceilDiv(UNITS_PER_TOKEN - units, refillPerMilli)
        return ceilDiv(millis, MILLIS_PER_SECOND).coerceAtLeast(1)
    }

    private fun ceilDiv(a: Long, b: Long): Long = (a + b - 1) / b

    /**
     * 추적 중인 사용자가 상한을 넘으면, 가만히 둬도 이미 가득 찼을 버킷을 지운다.
     * 가득 찬 버킷은 새 버킷과 같으므로 지워도 동작이 바뀌지 않는다. 주기 작업 없이 접근할 때만 한다.
     */
    private fun evictIdleBucketsIfTooMany(now: Long) {
        if (buckets.size < MAX_TRACKED_USERS) return
        buckets.entries.removeIf { now - it.value.lastRefillMillis >= MILLIS_PER_MINUTE }
    }

    /** 같은 사용자의 연속 거절은 첫 번만 warn 이다. 루프가 돌면 로그가 거절 수만큼 쌓이는 걸 막는다. */
    private fun logRejection(userId: Long, firstInStreak: Boolean) {
        if (firstInStreak) {
            log.warn("job 접수 속도 제한: userId={}, perMinute={}", userId, settings.perMinute)
        } else {
            log.debug("job 접수 속도 제한(연속): userId={}, perMinute={}", userId, settings.perMinute)
        }
    }

    private data class Bucket(val units: Long, val lastRefillMillis: Long, val rejecting: Boolean)

    private sealed interface Decision {
        data object Passed : Decision

        data class Rejected(val retryAfterSeconds: Long, val firstInStreak: Boolean) : Decision
    }

    companion object {
        /** 이만큼 넘게 추적하면 오래 안 쓴 버킷을 정리한다. 사용자가 몇 명뿐이라 사실상 닿지 않는 그물이다. */
        const val MAX_TRACKED_USERS = 10_000
        private const val MILLIS_PER_MINUTE = 60_000L
        private const val MILLIS_PER_SECOND = 1_000L

        /** 토큰 1개의 정수 단위. 1분(ms) 동안 perMinute 개가 차도록 분당 ms 수와 같게 둔다. */
        private const val UNITS_PER_TOKEN = MILLIS_PER_MINUTE
    }
}
