package com.example.credit_system_kotlin.global.config

import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class AppPropertiesTest {

    @Test
    fun `유효한 heartbeat 설정은 생성된다`() {
        assertThatCode { AppProperties.Heartbeat(10, 5, 60) }.doesNotThrowAnyException()
    }

    @Test
    fun `refresh interval이 timeout 이상이면 거부한다`() {
        assertThatThrownBy { AppProperties.Heartbeat(10, 15, 60) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("refresh-interval-seconds")
            .hasMessageContaining("timeout-seconds")
    }

    @Test
    fun `refresh interval이 timeout과 같아도 거부한다`() {
        assertThatThrownBy { AppProperties.Heartbeat(10, 10, 60) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("작아야 합니다")
    }

    @Test
    fun `timeout이 1 미만이면 거부한다`() {
        assertThatThrownBy { AppProperties.Heartbeat(0, 5, 60) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("timeout-seconds는 1 이상")
    }

    @Test
    fun `refresh interval이 1 미만이면 거부한다`() {
        assertThatThrownBy { AppProperties.Heartbeat(10, 0, 60) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("refresh-interval-seconds는 1 이상")
    }

    @Test
    fun `suppression alert가 1 미만이면 거부한다`() {
        assertThatThrownBy { AppProperties.Heartbeat(10, 5, 0) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("suppression-alert-seconds는 1 이상")
    }
}
