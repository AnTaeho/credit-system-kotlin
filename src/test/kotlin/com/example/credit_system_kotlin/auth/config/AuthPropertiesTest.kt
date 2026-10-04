package com.example.credit_system_kotlin.auth.config

import com.example.credit_system_kotlin.user.domain.UserRole
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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
    fun `아무것도 주지 않으면 쿠키는 Secure 가 아니고 시드 계정은 없다`() {
        runner.run { context ->
            val properties = context.getBean(AuthProperties::class.java)

            assertThat(properties.cookieSecure).isFalse()
            assertThat(properties.seedAccounts).isEmpty()
        }
    }

    @Test
    fun `시드 계정은 설정에서 목록으로 묶이고 역할을 적지 않으면 USER 다`() {
        runner
            .withPropertyValues(
                "app.auth.cookie-secure=true",
                "app.auth.seed-accounts[0].email=boss@test.local",
                "app.auth.seed-accounts[0].password=boss-password",
                "app.auth.seed-accounts[0].role=ADMIN",
                "app.auth.seed-accounts[1].email=dev@test.local",
                "app.auth.seed-accounts[1].password=dev-password",
                // 같은 접두사 아래 JwtProperties 의 값이 있어도 묶는 데 걸리지 않는다.
                "app.auth.jwt.secret=test-only-jwt-secret-0123456789-abcdef"
            )
            .run { context ->
                val properties = context.getBean(AuthProperties::class.java)

                assertThat(properties.cookieSecure).isTrue()
                assertThat(properties.seedAccounts).containsExactly(
                    AuthProperties.SeedAccount("boss@test.local", "boss-password", UserRole.ADMIN),
                    AuthProperties.SeedAccount("dev@test.local", "dev-password", UserRole.USER)
                )
            }
    }

    @Test
    fun `같은 이메일을 두 번 적은 시드 계정은 대소문자가 달라도 거부한다`() {
        assertThatThrownBy {
            AuthProperties(
                seedAccounts = listOf(
                    AuthProperties.SeedAccount("boss@test.local", "boss-password", UserRole.ADMIN),
                    AuthProperties.SeedAccount(" Boss@Test.Local", "other-password", UserRole.USER)
                )
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("seed-accounts")
    }

    @Test
    fun `시드 계정을 문자열로 찍어도 비밀번호가 나오지 않는다`() {
        val properties = AuthProperties(
            seedAccounts = listOf(AuthProperties.SeedAccount("boss@test.local", "boss-password", UserRole.ADMIN))
        )

        assertThat(properties.toString()).doesNotContain("boss-password").contains("b***@test.local")
    }

    @Test
    fun `로그용 이메일 마스킹은 로컬 파트를 가린다`() {
        assertThat(maskEmail("alice@example.com")).isEqualTo("a***@example.com")
        assertThat(maskEmail(null)).isEqualTo("(없음)")
        assertThat(maskEmail("no-at-sign")).isEqualTo("***")
    }
}
