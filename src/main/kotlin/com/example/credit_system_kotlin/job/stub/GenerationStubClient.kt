package com.example.credit_system_kotlin.job.stub

import com.example.credit_system_kotlin.global.config.AppProperties
import com.example.credit_system_kotlin.global.exception.StubGenerationException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom

private val log = LoggerFactory.getLogger(GenerationStubClient::class.java)

@Component
class GenerationStubClient(
    private val appProperties: AppProperties
) {

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
