package com.example.credit_system_kotlin.auth.config

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class AuthPropertiesTest {

    @Test
    fun `허용·운영자 판정은 대소문자와 앞뒤 공백을 무시한다`() {
        val properties = AuthProperties(
            allowedEmails = listOf(" Me@Test.Local", "boss@test.local"),
            adminEmails = listOf("BOSS@test.local")
        )

        assertThat(properties.isAllowed("me@test.local")).isTrue()
        assertThat(properties.isAllowed("ME@TEST.LOCAL")).isTrue()
        assertThat(properties.isAdmin("boss@TEST.local")).isTrue()
        assertThat(properties.isAdmin("me@test.local")).isFalse()
        assertThat(properties.isAllowed("stranger@test.local")).isFalse()
    }

    @Test
    fun `운영자는 허용 목록 안에 있어야 한다`() {
        assertThatThrownBy {
            AuthProperties(allowedEmails = listOf("me@test.local"), adminEmails = listOf("boss@test.local"))
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("admin-emails")
    }

    @Test
    fun `로그용 이메일 마스킹은 로컬 파트를 가린다`() {
        assertThat(maskEmail("alice@example.com")).isEqualTo("a***@example.com")
        assertThat(maskEmail(null)).isEqualTo("(없음)")
        assertThat(maskEmail("no-at-sign")).isEqualTo("***")
    }
}
