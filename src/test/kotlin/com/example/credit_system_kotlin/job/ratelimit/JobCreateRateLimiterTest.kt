package com.example.credit_system_kotlin.job.ratelimit

import com.example.credit_system_kotlin.global.config.AppProperties
import com.example.credit_system_kotlin.global.config.appProperties
import com.example.credit_system_kotlin.global.event.DefenseOutcome
import com.example.credit_system_kotlin.global.event.DefensePoint
import com.example.credit_system_kotlin.global.exception.RateLimitedException
import com.example.credit_system_kotlin.job.concurrency.runConcurrently
import com.example.credit_system_kotlin.support.FixedMutableClock
import com.example.credit_system_kotlin.support.RecordingEventPublisher
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

class JobCreateRateLimiterTest {

    private val clock = FixedMutableClock(Instant.parse("2026-09-19T00:00:00Z"))
    private val events = RecordingEventPublisher()

    private fun limiter(perMinute: Int = 3, enabled: Boolean = true) = JobCreateRateLimiter(
        appProperties(rateLimit = AppProperties.RateLimit(AppProperties.JobCreate(enabled, perMinute))),
        clock,
        events
    )

    private fun JobCreateRateLimiter.passes(userId: Long): Boolean =
        try {
            acquire(userId)
            true
        } catch (e: RateLimitedException) {
            false
        }

    @Test
    fun `용량만큼 통과한 뒤 거절한다`() {
        val limiter = limiter(perMinute = 3)

        val results = (1..4).map { limiter.passes(USER) }

        assertThat(results).containsExactly(true, true, true, false)
    }

    @Test
    fun `시간이 지나면 균등하게 다시 찬다`() {
        val limiter = limiter(perMinute = 3) // 20초에 1개
        repeat(3) { limiter.acquire(USER) }

        clock.advance(Duration.ofSeconds(19))
        assertThat(limiter.passes(USER)).isFalse()

        clock.advance(Duration.ofSeconds(1))
        assertThat(limiter.passes(USER)).isTrue()
        assertThat(limiter.passes(USER)).isFalse()
    }

    @Test
    fun `오래 쉬어도 용량 이상으로 쌓이지 않는다`() {
        val limiter = limiter(perMinute = 3)

        clock.advance(Duration.ofHours(1))
        val results = (1..4).map { limiter.passes(USER) }

        assertThat(results).containsExactly(true, true, true, false)
    }

    @Test
    fun `사용자마다 버킷이 따로다`() {
        val limiter = limiter(perMinute = 2)
        repeat(2) { limiter.acquire(USER) }

        assertThat(limiter.passes(USER)).isFalse()
        assertThat(limiter.passes(OTHER)).isTrue()
        assertThat(limiter.passes(OTHER)).isTrue()
        assertThat(limiter.passes(OTHER)).isFalse()
    }

    @Test
    fun `Retry-After 는 다음 토큰까지 남은 초를 올림한다`() {
        val limiter = limiter(perMinute = 3) // 20초에 1개
        repeat(3) { limiter.acquire(USER) }

        assertThat(retryAfter(limiter)).isEqualTo(20)

        clock.advance(Duration.ofMillis(5_500))
        assertThat(retryAfter(limiter)).isEqualTo(15) // 14.5초 → 15

        clock.advance(Duration.ofMillis(14_499))
        assertThat(retryAfter(limiter)).isEqualTo(1) // 1ms 남아도 0초라고 답하지 않는다
    }

    @Test
    fun `분당 한도가 60을 넘으면 Retry-After 는 최소 1초다`() {
        val limiter = limiter(perMinute = 600) // 0.1초에 1개
        repeat(600) { limiter.acquire(USER) }

        assertThat(retryAfter(limiter)).isEqualTo(1)
    }

    @Test
    fun `꺼져 있으면 항상 통과하고 지표도 세지 않는다`() {
        val limiter = limiter(perMinute = 1, enabled = false)

        assertThatCode { repeat(100) { limiter.acquire(USER) } }.doesNotThrowAnyException()
        assertThat(events.defenseEvents()).isEmpty()
    }

    @Test
    fun `통과와 거절을 RATE_LIMIT 방어 이벤트로 발행한다`() {
        val limiter = limiter(perMinute = 2)

        repeat(5) { limiter.passes(USER) }

        assertThat(events.countOf(DefensePoint.RATE_LIMIT, DefenseOutcome.APPLIED)).isEqualTo(2)
        assertThat(events.countOf(DefensePoint.RATE_LIMIT, DefenseOutcome.REJECTED)).isEqualTo(3)
        assertThat(events.defenseEvents()).allSatisfy { assertThat(it.point).isEqualTo(DefensePoint.RATE_LIMIT) }
    }

    @Test
    fun `같은 사용자가 동시에 몰려도 용량을 넘겨 통과하지 않는다`() {
        val capacity = 5
        val threads = 50
        val limiter = limiter(perMinute = capacity)
        val passed = AtomicInteger()
        val rejected = AtomicInteger()

        runConcurrently(threads) {
            if (limiter.passes(USER)) passed.incrementAndGet() else rejected.incrementAndGet()
        }

        assertThat(passed.get()).isEqualTo(capacity)
        assertThat(rejected.get()).isEqualTo(threads - capacity)
    }

    @Test
    fun `분당 한도가 1 미만이면 설정 단계에서 거부한다`() {
        assertThatThrownBy { AppProperties.JobCreate(perMinute = 0) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun retryAfter(limiter: JobCreateRateLimiter): Long =
        try {
            limiter.acquire(USER)
            error("거절돼야 한다")
        } catch (e: RateLimitedException) {
            e.retryAfterSeconds
        }

    companion object {
        private const val USER = 1L
        private const val OTHER = 2L
    }
}
