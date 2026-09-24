package com.example.credit_system_kotlin.global.bulkhead

import com.example.credit_system_kotlin.observability.IntakeBulkheadMetrics
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 벌크헤드가 실제로 **막는지**를 본다.
 *
 * "동시 진입이 상한 이하였다"는 관찰은 테스트가 어쩌다 직렬로 돌기만 해도 통과한다. 그래서
 * 상한만큼을 붙잡아 둔 채 한 건을 더 밀어 넣고, 그 한 건이 **들어가지 못하는 것**과 앞의 하나가
 * 풀리면 **들어가는 것**을 둘 다 확인한다.
 */
class IntakeBulkheadFilterTest {

    private val registry = SimpleMeterRegistry()

    private fun filterWith(permits: Semaphore): IntakeBulkheadFilter =
        IntakeBulkheadFilter(permits, IntakeBulkheadMetrics(registry, permits, permits.availablePermits()))

    private fun intakeRequest() = MockHttpServletRequest("POST", "/api/generations")

    @Test
    fun `상한을 넘은 요청은 앞이 끝날 때까지 들어가지 못한다`() {
        val permits = Semaphore(1, true)
        val filter = filterWith(permits)
        val holderEntered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)

        val holder = Thread {
            filter.doFilter(intakeRequest(), MockHttpServletResponse(), chainThat(holderEntered, release))
        }
        val second = Thread {
            filter.doFilter(intakeRequest(), MockHttpServletResponse(), chainThat(secondEntered, null))
        }
        try {
            holder.start()
            assertThat(holderEntered.await(AWAIT_SECONDS, TimeUnit.SECONDS)).isTrue()

            second.start()
            // 허가가 없으므로 들어가지 못한다. 이 기다림이 "막는다"의 증거다.
            assertThat(secondEntered.await(BLOCKED_MILLIS, TimeUnit.MILLISECONDS)).isFalse()

            release.countDown()
            assertThat(secondEntered.await(AWAIT_SECONDS, TimeUnit.SECONDS)).isTrue()
        } finally {
            release.countDown()
            holder.join(JOIN_MILLIS)
            second.join(JOIN_MILLIS)
        }

        assertThat(permits.availablePermits()).isEqualTo(1)
    }

    @Test
    fun `동시에 들어간 수가 상한을 넘지 않는다`() {
        val permits = Semaphore(PERMITS, true)
        val filter = filterWith(permits)
        val inside = Semaphore(0)
        val peak = AtomicReference(0)
        val current = java.util.concurrent.atomic.AtomicInteger(0)
        val release = CountDownLatch(1)
        val allDone = CountDownLatch(THREADS)

        val threads = (1..THREADS).map {
            Thread {
                try {
                    val chain = FilterChain { _, _ ->
                        val now = current.incrementAndGet()
                        peak.updateAndGet { seen -> maxOf(seen, now) }
                        inside.release()
                        release.await(AWAIT_SECONDS, TimeUnit.SECONDS)
                        current.decrementAndGet()
                    }
                    filter.doFilter(intakeRequest(), MockHttpServletResponse(), chain)
                } finally {
                    allDone.countDown()
                }
            }
        }
        try {
            threads.forEach { it.start() }
            // 상한만큼은 반드시 들어간다(상한이 과하게 조이지 않는다).
            assertThat(inside.tryAcquire(PERMITS, AWAIT_SECONDS, TimeUnit.SECONDS)).isTrue()
            assertThat(peak.get()).isEqualTo(PERMITS)
        } finally {
            release.countDown()
            assertThat(allDone.await(AWAIT_SECONDS, TimeUnit.SECONDS)).isTrue()
            threads.forEach { it.join(JOIN_MILLIS) }
        }

        assertThat(peak.get()).isEqualTo(PERMITS)
        assertThat(permits.availablePermits()).isEqualTo(PERMITS)
    }

    @Test
    fun `접수 밖 경로는 허가가 없어도 그냥 통과한다`() {
        val permits = Semaphore(1, true)
        val filter = filterWith(permits)
        permits.acquire() // 허가를 모두 소진한 상태

        listOf("/login", "/admin", "/actuator/prometheus", "/css/app.css").forEach { path ->
            val chain = MockFilterChain()

            filter.doFilter(MockHttpServletRequest("GET", path), MockHttpServletResponse(), chain)

            assertThat(chain.request).describedAs(path).isNotNull()
        }
        assertThat(permits.availablePermits()).isZero()
    }

    @Test
    fun `체인에서 예외가 나도 허가는 돌아온다`() {
        val permits = Semaphore(1, true)
        val filter = filterWith(permits)

        val exploding = FilterChain { _, _ -> throw IllegalStateException("터졌다") }

        assertThatThrownBy {
            filter.doFilter(intakeRequest(), MockHttpServletResponse(), exploding)
        }.isInstanceOf(IllegalStateException::class.java)

        assertThat(permits.availablePermits()).isEqualTo(1)
    }

    @Test
    fun `허가를 기다리다 인터럽트되면 받지 않은 허가를 반납하지 않는다`() {
        val permits = Semaphore(1, true)
        val filter = filterWith(permits)
        permits.acquire()
        val entered = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)

        val waiter = Thread {
            try {
                filter.doFilter(intakeRequest(), MockHttpServletResponse(), chainThat(entered, null))
            } catch (t: Throwable) {
                failure.set(t)
            }
        }
        waiter.start()
        assertThat(entered.await(BLOCKED_MILLIS, TimeUnit.MILLISECONDS)).isFalse()

        waiter.interrupt()
        waiter.join(JOIN_MILLIS)

        assertThat(failure.get()).isInstanceOf(ServletException::class.java)
        assertThat(entered.count).isEqualTo(1)
        // 받지 못한 허가를 반납했다면 여기가 1 이 된다 — 상한이 조용히 늘어난다.
        assertThat(permits.availablePermits()).isZero()
    }

    private fun chainThat(entered: CountDownLatch, release: CountDownLatch?) = FilterChain { _, _ ->
        entered.countDown()
        release?.await(AWAIT_SECONDS, TimeUnit.SECONDS)
    }

    companion object {
        private const val PERMITS = 2
        private const val THREADS = 6
        private const val AWAIT_SECONDS = 10L
        private const val BLOCKED_MILLIS = 300L
        private const val JOIN_MILLIS = 10_000L
    }
}
