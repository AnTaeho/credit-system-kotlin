package com.example.credit_system_kotlin.global.config

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * 절대 상한이 후보 선정 기준보다 크지 않으면 백스톱이 아니라 그냥 짧은 타임아웃이 된다.
 * heartbeat 가 살아 있는 정상 job 까지 후보가 되는 즉시 회수되므로 기동에서 막는다.
 */
class ProcessingPropertiesTest {

    @Test
    fun `절대 상한이 더 크면 생성된다`() {
        assertThatCode { AppProperties.Processing(60, 61) }.doesNotThrowAnyException()
    }

    @Test
    fun `생략하면 기본 절대 상한은 300초다`() {
        assertThat(AppProperties.Processing(60).absoluteTimeoutSeconds)
            .isEqualTo(AppProperties.DEFAULT_ABSOLUTE_TIMEOUT_SECONDS)
            .isEqualTo(300L)
    }

    @Test
    fun `절대 상한이 timeout과 같으면 거부한다`() {
        assertThatThrownBy { AppProperties.Processing(60, 60) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("absolute-timeout-seconds")
            .hasMessageContaining("timeout-seconds")
    }

    @Test
    fun `절대 상한이 timeout보다 작으면 거부한다`() {
        assertThatThrownBy { AppProperties.Processing(60, 30) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("커야 합니다")
    }
}
