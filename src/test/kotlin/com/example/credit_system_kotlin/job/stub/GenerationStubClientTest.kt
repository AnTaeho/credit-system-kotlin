package com.example.credit_system_kotlin.job.stub

import com.example.credit_system_kotlin.global.config.AppProperties
import com.example.credit_system_kotlin.global.config.appProperties
import com.example.credit_system_kotlin.global.exception.StubGenerationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

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
}
