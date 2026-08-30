package com.example.credit_system_kotlin.heartbeat

import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class HeartbeatPropertiesTest {

    @Test
    fun `유효한 heartbeat 설정은 생성된다`() {
        assertThatCode { HeartbeatProperties(10, 5) }.doesNotThrowAnyException()
    }

    @Test
    fun `refresh interval이 timeout 이상이면 거부한다`() {
        assertThatThrownBy { HeartbeatProperties(10, 15) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("refresh-interval-seconds")
            .hasMessageContaining("timeout-seconds")
    }

    @Test
    fun `refresh interval이 timeout과 같아도 거부한다`() {
        assertThatThrownBy { HeartbeatProperties(10, 10) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("작아야 합니다")
    }

    @Test
    fun `timeout이 1 미만이면 거부한다`() {
        assertThatThrownBy { HeartbeatProperties(0, 5) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("timeout-seconds는 1 이상")
    }

    @Test
    fun `refresh interval이 1 미만이면 거부한다`() {
        assertThatThrownBy { HeartbeatProperties(10, 0) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("refresh-interval-seconds는 1 이상")
    }
}
