package com.example.credit_system_kotlin.heartbeat

import com.example.credit_system_kotlin.global.config.WorkerProperties
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeast
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.timeout
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.data.redis.RedisConnectionFailureException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ZSetOperations
import org.springframework.test.util.ReflectionTestUtils
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ScheduledThreadPoolExecutor

@ExtendWith(MockitoExtension::class)
class HeartbeatRegistryTest {

    @Mock lateinit var redisTemplate: StringRedisTemplate

    @Mock lateinit var zSetOperations: ZSetOperations<String, String>

    private lateinit var registry: HeartbeatRegistry
    private lateinit var clock: MutableClock

    @BeforeEach
    fun setUp() {
        val properties = HeartbeatProperties(
            timeoutSeconds = 10, refreshIntervalSeconds = 1, suppressionAlertSeconds = 60
        )
        clock = MutableClock(Instant.now())
        registry = HeartbeatRegistry(redisTemplate, properties, WorkerProperties(true, 20, 3), clock)
    }

    @AfterEach
    fun tearDown() {
        registry.shutdown()
    }

    @Test
    fun `refreshHeartbeat가 Redis 예외를 삼키고 갱신 스레드를 죽이지 않는다`() {
        whenever(redisTemplate.opsForZSet()).thenReturn(zSetOperations)
        whenever(zSetOperations.add(any<String>(), any<String>(), any<Double>()))
            .thenThrow(RedisConnectionFailureException("redis down"))

        val future = registry.startHeartbeat(1L, 0)

        verify(zSetOperations, timeout(5000).atLeast(3)).add(eq(KEY), eq("1:0"), any<Double>())
        assertThat(future.isDone).isFalse()
        future.cancel(false)
    }

    @Test
    fun `findExpiredAttempts는 Redis 예외 시 빈 집합을 반환한다`() {
        whenever(redisTemplate.opsForZSet()).thenReturn(zSetOperations)
        whenever(zSetOperations.rangeByScore(eq(KEY), eq(Double.NEGATIVE_INFINITY), any<Double>()))
            .thenThrow(RedisConnectionFailureException("redis down"))

        assertThat(registry.findExpiredAttempts()).isEmpty()
    }

    @Test
    fun `hasLiveHeartbeat는 Redis 예외 시 true를 반환한다`() {
        whenever(redisTemplate.opsForZSet()).thenReturn(zSetOperations)
        whenever(zSetOperations.score(KEY, "5:0"))
            .thenThrow(RedisConnectionFailureException("redis down"))

        assertThat(registry.hasLiveHeartbeat(5L, 0)).isTrue()
    }

    @Test
    fun `removeHeartbeat는 Redis 예외를 삼킨다`() {
        whenever(redisTemplate.opsForZSet()).thenReturn(zSetOperations)
        whenever(zSetOperations.remove(KEY, "7:0"))
            .thenThrow(RedisConnectionFailureException("redis down"))

        assertThatCode { registry.removeHeartbeat(7L, 0) }.doesNotThrowAnyException()
    }

    @Test
    fun `stopHeartbeat은 removeHeartbeat가 Redis 예외를 던져도 전파하지 않는다`() {
        whenever(redisTemplate.opsForZSet()).thenReturn(zSetOperations)
        whenever(zSetOperations.add(any<String>(), any<String>(), any<Double>()))
            .thenThrow(RedisConnectionFailureException("redis down"))
        whenever(zSetOperations.remove(KEY, "3:0"))
            .thenThrow(RedisConnectionFailureException("redis down"))

        val future = registry.startHeartbeat(3L, 0)

        assertThatCode { registry.stopHeartbeat(3L, 0, future) }.doesNotThrowAnyException()
        assertThat(future.isCancelled).isTrue()
    }

    @Test
    fun `정상 상황에서 refreshHeartbeat는 now에 timeout을 더한 score로 기록한다`() {
        whenever(redisTemplate.opsForZSet()).thenReturn(zSetOperations)
        val before = Instant.now().epochSecond

        val future = registry.startHeartbeat(9L, 0)
        future.cancel(false)

        val score = argumentCaptor<Double>()
        verify(zSetOperations, atLeast(1)).add(eq(KEY), eq("9:0"), score.capture())
        assertThat(score.lastValue)
            .isBetween((before + 10).toDouble(), (Instant.now().epochSecond + 10).toDouble())
    }

    @Test
    fun `정상 상황에서 findExpiredAttempts는 조회된 attempt를 반환한다`() {
        whenever(redisTemplate.opsForZSet()).thenReturn(zSetOperations)
        whenever(zSetOperations.rangeByScore(eq(KEY), eq(Double.NEGATIVE_INFINITY), any<Double>()))
            .thenReturn(setOf("11:0", "12:3"))

        assertThat(registry.findExpiredAttempts())
            .containsExactlyInAnyOrder(JobAttempt(11L, 0), JobAttempt(12L, 3))
    }

    @Test
    fun `findExpiredAttempts는 깨진 멤버를 zset에서 제거하고 정상 멤버만 돌려준다`() {
        whenever(redisTemplate.opsForZSet()).thenReturn(zSetOperations)
        whenever(zSetOperations.rangeByScore(eq(KEY), eq(Double.NEGATIVE_INFINITY), any<Double>()))
            .thenReturn(setOf("12", "abc", "1:x", "20:5"))

        assertThat(registry.findExpiredAttempts()).containsExactly(JobAttempt(20L, 5))

        verify(zSetOperations).remove(KEY, "12")
        verify(zSetOperations).remove(KEY, "abc")
        verify(zSetOperations).remove(KEY, "1:x")
        verify(zSetOperations, never()).remove(KEY, "20:5")
    }

    @Test
    fun `정상 상황에서 hasLiveHeartbeat는 score 만료 여부로 판정한다`() {
        whenever(redisTemplate.opsForZSet()).thenReturn(zSetOperations)
        whenever(zSetOperations.score(KEY, "13:0")).thenReturn((Instant.now().epochSecond + 30).toDouble())
        whenever(zSetOperations.score(KEY, "14:0")).thenReturn((Instant.now().epochSecond - 30).toDouble())
        whenever(zSetOperations.score(KEY, "15:0")).thenReturn(null)

        assertThat(registry.hasLiveHeartbeat(13L, 0)).isTrue()
        assertThat(registry.hasLiveHeartbeat(14L, 0)).isFalse()
        assertThat(registry.hasLiveHeartbeat(15L, 0)).isFalse()
    }

    @Test
    fun `정상 상황에서 removeHeartbeat는 ZSET 멤버를 제거한다`() {
        whenever(redisTemplate.opsForZSet()).thenReturn(zSetOperations)
        whenever(zSetOperations.remove(KEY, "16:0")).thenReturn(1L)

        registry.removeHeartbeat(16L, 0)

        verify(zSetOperations).remove(KEY, "16:0")
    }

    @Test
    fun `stopHeartbeat은 같은 jobId의 다른 attempt heartbeat를 지우지 않는다`() {
        whenever(redisTemplate.opsForZSet()).thenReturn(zSetOperations)
        whenever(zSetOperations.add(any<String>(), any<String>(), any<Double>())).thenReturn(true)

        val future = registry.startHeartbeat(1L, 0)

        registry.stopHeartbeat(1L, 0, future)

        verify(zSetOperations).remove(KEY, "1:0")
        verify(zSetOperations, never()).remove(KEY, "1:1")
    }

    @Test
    fun `Redis 복구 직후 유예 구간에는 findExpiredAttempts가 빈 집합을 반환한다`() {
        whenever(redisTemplate.opsForZSet()).thenReturn(zSetOperations)
        whenever(zSetOperations.rangeByScore(eq(KEY), eq(Double.NEGATIVE_INFINITY), any<Double>()))
            .thenThrow(RedisConnectionFailureException("redis down"))
            .thenReturn(setOf("21:0", "22:0"))

        assertThat(registry.findExpiredAttempts()).isEmpty()

        clock.advance(Duration.ofSeconds(9))
        assertThat(registry.findExpiredAttempts()).isEmpty()
        verify(zSetOperations, times(1)).rangeByScore(eq(KEY), eq(Double.NEGATIVE_INFINITY), any<Double>())

        clock.advance(Duration.ofSeconds(2))
        assertThat(registry.findExpiredAttempts())
            .containsExactlyInAnyOrder(JobAttempt(21L, 0), JobAttempt(22L, 0))
    }

    @Test
    fun `유예 구간에는 hasLiveHeartbeat가 true를 반환한다`() {
        whenever(redisTemplate.opsForZSet()).thenReturn(zSetOperations)
        whenever(zSetOperations.score(KEY, "31:0"))
            .thenThrow(RedisConnectionFailureException("redis down"))

        assertThat(registry.hasLiveHeartbeat(31L, 0)).isTrue()

        clock.advance(Duration.ofSeconds(9))
        assertThat(registry.hasLiveHeartbeat(32L, 0)).isTrue()
        verify(zSetOperations, never()).score(KEY, "32:0")

        clock.advance(Duration.ofSeconds(2))
        assertThat(registry.hasLiveHeartbeat(32L, 0)).isFalse()
        verify(zSetOperations).score(KEY, "32:0")
    }

    @Test
    fun `유예 구간에도 refreshHeartbeat는 계속 시도한다`() {
        whenever(redisTemplate.opsForZSet()).thenReturn(zSetOperations)
        whenever(zSetOperations.rangeByScore(eq(KEY), eq(Double.NEGATIVE_INFINITY), any<Double>()))
            .thenThrow(RedisConnectionFailureException("redis down"))

        registry.findExpiredAttempts()
        clock.advance(Duration.ofSeconds(1))

        val future = registry.startHeartbeat(41L, 0)
        future.cancel(false)

        verify(zSetOperations, atLeastOnce()).add(eq(KEY), eq("41:0"), any<Double>())
    }

    @Test
    fun `유예 구간에도 removeHeartbeat는 계속 시도한다`() {
        whenever(redisTemplate.opsForZSet()).thenReturn(zSetOperations)
        whenever(zSetOperations.rangeByScore(eq(KEY), eq(Double.NEGATIVE_INFINITY), any<Double>()))
            .thenThrow(RedisConnectionFailureException("redis down"))

        registry.findExpiredAttempts()
        clock.advance(Duration.ofSeconds(1))

        registry.removeHeartbeat(42L, 0)

        verify(zSetOperations).remove(KEY, "42:0")
    }

    @Test
    fun `refreshHeartbeat 실패가 findExpiredAttempts 회수를 보류시킨다`() {
        whenever(redisTemplate.opsForZSet()).thenReturn(zSetOperations)
        whenever(zSetOperations.add(any<String>(), any<String>(), any<Double>()))
            .thenThrow(RedisConnectionFailureException("redis down"))

        val future = registry.startHeartbeat(43L, 0)
        future.cancel(false)

        clock.advance(Duration.ofSeconds(9))
        assertThat(registry.findExpiredAttempts()).isEmpty()
        verify(zSetOperations, never()).rangeByScore(eq(KEY), eq(Double.NEGATIVE_INFINITY), any<Double>())
    }

    @Test
    fun `실패 이력이 없으면 유예가 걸리지 않는다`() {
        whenever(redisTemplate.opsForZSet()).thenReturn(zSetOperations)
        whenever(zSetOperations.rangeByScore(eq(KEY), eq(Double.NEGATIVE_INFINITY), any<Double>()))
            .thenReturn(setOf("51:0"))
        whenever(zSetOperations.score(KEY, "52:0")).thenReturn((clock.instant().epochSecond + 30).toDouble())

        assertThat(registry.findExpiredAttempts()).containsExactly(JobAttempt(51L, 0))
        assertThat(registry.hasLiveHeartbeat(52L, 0)).isTrue()

        verify(zSetOperations).rangeByScore(eq(KEY), eq(Double.NEGATIVE_INFINITY), any<Double>())
        verify(zSetOperations).score(KEY, "52:0")
    }

    @Test
    fun `heartbeat 스레드 풀은 워커 동시 실행 수만큼 만들어진다`() {
        val executor = ReflectionTestUtils.getField(registry, "executor") as ScheduledThreadPoolExecutor

        assertThat(executor.corePoolSize).isEqualTo(3)
    }

    companion object {
        private const val KEY = "heartbeats"
    }
}
