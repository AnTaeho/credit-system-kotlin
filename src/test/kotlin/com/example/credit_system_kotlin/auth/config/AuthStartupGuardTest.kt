package com.example.credit_system_kotlin.auth.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.core.io.ClassPathResource

/**
 * 설정이 잘못되면 앱이 **뜨지 않는다**는 것을 컨텍스트 기동으로 확인한다.
 * 바인딩(`app.auth.*` → [AuthProperties], [JwtProperties])과 가드([AuthStartupGuard])를 실제 스프링이 엮게 한다.
 */
class AuthStartupGuardTest {

    @Configuration
    @EnableConfigurationProperties(AuthProperties::class, JwtProperties::class)
    @Import(AuthStartupGuard::class)
    class GuardOnly

    private val runner = ApplicationContextRunner().withUserConfiguration(GuardOnly::class.java)

    @Test
    fun `prod 프로필에서 쿠키가 Secure 가 아니면 기동이 거부된다`() {
        runner
            .withPropertyValues("spring.profiles.active=prod", "app.auth.jwt.secret=$REAL_SECRET")
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).rootCause()
                    .hasMessageContaining("app.auth.cookie-secure 가 true 여야 합니다")
            }
    }

    @Test
    fun `prod 프로필에서 서명 키가 로컬 기본값이면 기동이 거부된다`() {
        runner
            .withPropertyValues(
                "spring.profiles.active=prod",
                "app.auth.cookie-secure=true",
                "app.auth.jwt.secret=${JwtProperties.LOCAL_DEFAULT_SECRET}"
            )
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).rootCause()
                    .hasMessageContaining("app.auth.jwt.secret 에 로컬 기본값을 쓸 수 없습니다")
            }
    }

    @Test
    fun `prod 에서 쿠키가 Secure 이고 서명 키를 따로 주면 뜬다`() {
        runner
            .withPropertyValues(
                "spring.profiles.active=prod",
                "app.auth.cookie-secure=true",
                "app.auth.jwt.secret=$REAL_SECRET"
            )
            .run { context -> assertThat(context).hasNotFailed() }
    }

    @Test
    fun `prod 가 아니면 Secure 없는 쿠키와 로컬 기본 키로도 뜬다`() {
        runner
            .withPropertyValues(
                "spring.profiles.active=local",
                "app.auth.jwt.secret=${JwtProperties.LOCAL_DEFAULT_SECRET}"
            )
            .run { context -> assertThat(context).hasNotFailed() }
    }

    /** 가드는 코드에 적힌 값과 비교한다. yml 의 기본값만 바뀌면 가드가 조용히 무력해지므로 둘을 묶어 둔다. */
    @Test
    fun `가드가 아는 로컬 기본 키는 application yml 에 적힌 기본값과 같다`() {
        val yml = ClassPathResource("application.yml").getContentAsString(Charsets.UTF_8)

        assertThat(yml).contains("secret: \${APP_AUTH_JWT_SECRET:${JwtProperties.LOCAL_DEFAULT_SECRET}}")
    }

    companion object {
        private const val REAL_SECRET = "a-real-secret-given-by-the-operator-0123456789"
    }
}
