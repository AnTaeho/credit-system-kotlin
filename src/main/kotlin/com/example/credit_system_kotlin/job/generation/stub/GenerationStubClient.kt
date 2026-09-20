package com.example.credit_system_kotlin.job.generation.stub

import com.example.credit_system_kotlin.global.config.AppProperties
import com.example.credit_system_kotlin.job.generation.GenerationClient
import com.example.credit_system_kotlin.job.generation.GenerationTimeoutException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadLocalRandom

private val log = LoggerFactory.getLogger(GenerationStubClient::class.java)

/**
 * 진짜 생성기가 붙기 전까지 외부를 흉내 내는 구현. 동시에 **장애 주입 손잡이**다.
 *
 * 낼 수 있는 모습은 넷이다.
 * - 정상 지연: `app.stub.min-delay-millis` ~ `max-delay-millis`
 * - 확률적 실패: `app.stub.failure-rate`
 * - 타임아웃: 지연이 `app.generation.timeout-seconds` 를 넘으면 상한까지만 기다리고 끊는다
 * - 응답 없음(hang): `app.stub.hang=true` 면 타임아웃조차 먹지 않고 스레드를 붙잡는다
 *
 * 타임아웃은 **별도 스레드 없이** 구현한다. 지연을 어차피 우리가 정하므로 `min(지연, 상한)` 만큼만
 * 자고 상한을 넘었으면 던지면 된다. 감시 스레드도 인터럽트도 없으니 워커 스레드에 인터럽트
 * 플래그가 남아 다음 task 를 망칠 일이 없다.
 */
@Component
class GenerationStubClient(
    private val appProperties: AppProperties
) : GenerationClient {

    /**
     * hang 모드를 깨우는 유일한 수단. **테스트·시나리오 정리용이다.**
     *
     * 운영에서는 아무도 세지 않으므로 스레드가 영영 묶인다. 그게 hang 모드의 목적이고,
     * 바깥쪽 절대 상한이 왜 필요한지를 증언하는 자리다.
     */
    private val hangLatch = CountDownLatch(1)

    override fun generate(prompt: String): String {
        val stub = appProperties.stub
        val timeoutMillis = appProperties.generation.timeoutMillis()

        if (stub.hang) {
            hangForever(prompt, timeoutMillis)
        }

        val delayMillis = randomDelayMillis(stub)
        if (delayMillis > timeoutMillis) {
            // 상한까지는 실제로 기다린다. "기다리다 끊겼다"와 "즉시 거절"은 다른 사건이다.
            sleep(timeoutMillis)
            log.info("stub generation timed out: prompt={}, timeoutMillis={}", prompt, timeoutMillis)
            throw GenerationTimeoutException(prompt, timeoutMillis)
        }
        sleep(delayMillis)

        if (ThreadLocalRandom.current().nextDouble() < stub.failureRate) {
            log.info("stub generation failed: prompt={}", prompt)
            throw StubGenerationException(prompt)
        }

        val resultUrl = "https://stub-images.local/${UUID.randomUUID()}.png"
        log.info("stub generation succeeded: prompt={}, resultUrl={}", prompt, resultUrl)
        return resultUrl
    }

    /** 테스트가 hang 에 묶인 스레드를 풀어 준다. 풀려난 호출은 성공하지 않고 타임아웃으로 끝난다. */
    fun releaseHang() {
        hangLatch.countDown()
    }

    /**
     * 깨어나도 절대 성공으로 돌아가지 않는다. 이미 회수·환불이 끝난 job 을 뒤늦게 confirm 하는
     * 일이 없어야 하므로, 풀려난 호출은 타임아웃으로 끝낸다.
     */
    private fun hangForever(prompt: String, timeoutMillis: Long): Nothing {
        log.warn("stub generation hanging: prompt={}", prompt)
        try {
            hangLatch.await()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("stub hang 중 인터럽트 발생", e)
        }
        throw GenerationTimeoutException(prompt, timeoutMillis)
    }

    private fun randomDelayMillis(stub: AppProperties.Stub): Long {
        if (stub.maxDelayMillis <= stub.minDelayMillis) {
            return stub.minDelayMillis
        }
        return ThreadLocalRandom.current().nextLong(stub.minDelayMillis, stub.maxDelayMillis + 1)
    }

    private fun sleep(millis: Long) {
        if (millis <= 0) {
            return
        }
        try {
            Thread.sleep(millis)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("stub 지연 중 인터럽트 발생", e)
        }
    }
}
