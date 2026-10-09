package com.example.credit_system_kotlin.unit.auth.config

import com.example.credit_system_kotlin.auth.config.maskEmail
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AuthPropertiesTest {

    @Test
    fun `로그용 이메일 마스킹은 로컬 파트를 가린다`() {
        assertThat(maskEmail("alice@example.com")).isEqualTo("a***@example.com")
        assertThat(maskEmail(null)).isEqualTo("(없음)")
        assertThat(maskEmail("no-at-sign")).isEqualTo("***")
    }
}
