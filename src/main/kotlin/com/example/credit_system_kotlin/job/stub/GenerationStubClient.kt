package com.example.credit_system_kotlin.job.stub

import com.example.credit_system_kotlin.global.config.AppProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom

private val log = LoggerFactory.getLogger(GenerationStubClient::class.java)

/** 외부 이미지 생성 API 대역. 설정한 범위에서 무작위로 지연하고 설정한 확률로 실패한다. */
@Component
class GenerationStubClient(
    private val appProperties: AppProperties
) {

    /** 지연한 뒤 failureRate 확률로 [StubGenerationException] 을 던지고, 아니면 가짜 결과 URL 을 돌려준다. */
    fun generate(prompt: String): String {
        val stub = appProperties.stub
        sleep(randomDelayMillis(stub))

        if (ThreadLocalRandom.current().nextDouble() < stub.failureRate) {
            log.info("stub generation failed: prompt={}", prompt)
            throw StubGenerationException(prompt)
        }

        val resultUrl = "https://stub-images.local/${UUID.randomUUID()}.png"
        log.info("stub generation succeeded: prompt={}, resultUrl={}", prompt, resultUrl)
        return resultUrl
    }

    /** 최대가 최소보다 크지 않으면 최소를 그대로 쓴다. 최대도 범위에 든다. */
    private fun randomDelayMillis(stub: AppProperties.Stub): Long {
        if (stub.maxDelayMillis <= stub.minDelayMillis) {
            return stub.minDelayMillis
        }
        return ThreadLocalRandom.current().nextLong(stub.minDelayMillis, stub.maxDelayMillis + 1)
    }

    /** 인터럽트되면 플래그를 되살리고 IllegalStateException 으로 바꿔 던진다. 워커는 이를 생성 실패로 처리한다. */
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
