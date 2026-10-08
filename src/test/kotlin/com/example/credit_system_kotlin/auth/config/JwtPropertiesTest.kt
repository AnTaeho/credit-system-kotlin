package com.example.credit_system_kotlin.auth.config

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration

class JwtPropertiesTest {

    @Test
    fun `서명 키가 32바이트보다 짧으면 만들 수 없다`() {
        assertThatThrownBy { JwtProperties(secret = "a".repeat(31)) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("32바이트 이상")
    }

    @Test
    fun `32바이트 키면 기본 수명으로 만들어지고 toString 에 키가 찍히지 않는다`() {
        val properties = JwtProperties(secret = "a".repeat(32))

        assertThat(properties.issuer).isEqualTo("credit")
        assertThat(properties.accessTtl).isEqualTo(Duration.ofHours(1))
        assertThat(properties.refreshTtl).isEqualTo(Duration.ofDays(14))
        assertThat(properties.toString()).doesNotContain("a".repeat(32))
    }
}
