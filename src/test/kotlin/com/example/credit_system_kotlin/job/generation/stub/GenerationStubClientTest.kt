package com.example.credit_system_kotlin.job.generation.stub

import com.example.credit_system_kotlin.global.config.AppProperties
import com.example.credit_system_kotlin.global.config.appProperties
import com.example.credit_system_kotlin.job.generation.GenerationTimeoutException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class GenerationStubClientTest {

    @Test
    fun `failureRate가 0이면 항상 성공하고 결과 URL을 반환한다`() {
        val client = GenerationStubClient(appProperties(stub = AppProperties.Stub(0.0, 0, 0)))

        val resultUrl = client.generate("a cat wearing sunglasses")

        assertThat(resultUrl).startsWith("https://stub-images.local/")
    }

    @Test
    fun `failureRate가 1이면 항상 실패한다`() {
        val client = GenerationStubClient(appProperties(stub = AppProperties.Stub(1.0, 0, 0)))

        assertThatThrownBy { client.generate("a cat wearing sunglasses") }
            .isInstanceOf(StubGenerationException::class.java)
    }

    @Test
    fun `지연이 상한 안이면 정상 결과를 돌려준다`() {
        val client = GenerationStubClient(
            appProperties(
                generation = generation(timeoutSeconds = 2),
                stub = AppProperties.Stub(0.0, 100, 100)
            )
        )

        val resultUrl = client.generate("cat")

        assertThat(resultUrl).startsWith("https://stub-images.local/")
    }

    @Test
    fun `지연이 상한을 넘으면 상한까지만 기다린 뒤 타임아웃으로 끊는다`() {
        val client = GenerationStubClient(
            appProperties(
                generation = generation(timeoutSeconds = 1),
                stub = AppProperties.Stub(0.0, 5_000, 5_000)
            )
        )

        val startedAt = System.nanoTime()
        assertThatThrownBy { client.generate("cat") }
            .isInstanceOf(GenerationTimeoutException::class.java)
        val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000

        // 하한: 즉시 거절이 아니라 상한까지 실제로 기다렸다. 상한: 5초짜리 지연을 다 자지 않았다.
        assertThat(elapsedMillis).isBetween(900L, 3_000L)
    }

    @Test
    fun `hang 모드는 상한이 지나도 돌아오지 않는다`() {
        val client = GenerationStubClient(
            appProperties(
                generation = generation(timeoutSeconds = 1),
                stub = AppProperties.Stub(0.0, 0, 0, hang = true)
            )
        )
        val finished = CountDownLatch(1)
        var thrown: Throwable? = null

        val worker = thread(name = "hang-test") {
            try {
                client.generate("cat")
            } catch (e: Throwable) {
                thrown = e
            } finally {
                finished.countDown()
            }
        }
        try {
            // 타임아웃(1초)의 두 배를 기다려도 돌아오지 않는다 = 이 모드는 타임아웃조차 먹지 않는다.
            assertThat(finished.await(2, TimeUnit.SECONDS)).isFalse()
        } finally {
            // 테스트가 영원히 매달리지 않도록 반드시 깨운다.
            client.releaseHang()
        }

        assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue()
        worker.join(5_000)
        // 깨어나도 성공으로 돌아가지 않는다. 늦은 confirm 이 회수 끝난 job 을 되살리면 안 된다.
        assertThat(thrown).isInstanceOf(GenerationTimeoutException::class.java)
    }

    private fun generation(timeoutSeconds: Long) =
        AppProperties.Generation(cost = 100L, maxAttempts = 3, timeoutSeconds = timeoutSeconds)
}
