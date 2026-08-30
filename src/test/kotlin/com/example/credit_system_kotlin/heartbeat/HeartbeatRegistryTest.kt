package com.example.credit_system_kotlin.heartbeat

import com.example.credit_system_kotlin.global.config.WorkerProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeast
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ZSetOperations
import org.springframework.test.util.ReflectionTestUtils
import java.time.Instant
import java.util.concurrent.ScheduledThreadPoolExecutor

@ExtendWith(MockitoExtension::class)
class HeartbeatRegistryTest {

    @Mock lateinit var redisTemplate: StringRedisTemplate

    @Mock lateinit var zSetOperations: ZSetOperations<String, String>

    private lateinit var registry: HeartbeatRegistry

    @BeforeEach
    fun setUp() {
        val properties = HeartbeatProperties(
            timeoutSeconds = 10, refreshIntervalSeconds = 1
        )
        registry = HeartbeatRegistry(redisTemplate, properties, WorkerProperties(true, 20, 3))
    }

    @AfterEach
    fun tearDown() {
        registry.shutdown()
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
    fun `heartbeat 스레드 풀은 워커 동시 실행 수만큼 만들어진다`() {
        val executor = ReflectionTestUtils.getField(registry, "executor") as ScheduledThreadPoolExecutor

        assertThat(executor.corePoolSize).isEqualTo(3)
    }

    companion object {
        private const val KEY = "heartbeats"
    }
}
