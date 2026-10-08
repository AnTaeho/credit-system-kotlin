package com.example.credit_system_kotlin.auth.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration

class AuthPropertiesTest {

    @Configuration
    @EnableConfigurationProperties(AuthProperties::class)
    class PropertiesOnly

    private val runner = ApplicationContextRunner().withUserConfiguration(PropertiesOnly::class.java)

    @Test
    fun `아무것도 주지 않으면 쿠키는 Secure 가 아니다`() {
        runner.run { context ->
            val properties = context.getBean(AuthProperties::class.java)

            assertThat(properties.cookieSecure).isFalse()
        }
    }

    @Test
    fun `cookie-secure 는 설정에서 묶인다`() {
        runner
            .withPropertyValues(
                "app.auth.cookie-secure=true",
                // 같은 접두사 아래 JwtProperties 의 값이 있어도 묶는 데 걸리지 않는다.
                "app.auth.jwt.secret=test-only-jwt-secret-0123456789-abcdef"
            )
            .run { context ->
                val properties = context.getBean(AuthProperties::class.java)

                assertThat(properties.cookieSecure).isTrue()
            }
    }

    @Test
    fun `로그용 이메일 마스킹은 로컬 파트를 가린다`() {
        assertThat(maskEmail("alice@example.com")).isEqualTo("a***@example.com")
        assertThat(maskEmail(null)).isEqualTo("(없음)")
        assertThat(maskEmail("no-at-sign")).isEqualTo("***")
    }
}
