package com.example.credit_system_kotlin.auth.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import

/**
 * 설정이 잘못되면 앱이 **뜨지 않는다**는 것을 컨텍스트 기동으로 확인한다.
 * 바인딩(`app.auth.*` → [AuthProperties])과 가드([AuthStartupGuard])를 실제 스프링이 엮게 한다.
 */
class AuthStartupGuardTest {

    @Configuration
    @EnableConfigurationProperties(AuthProperties::class)
    @Import(AuthStartupGuard::class)
    class GuardOnly

    private val runner = ApplicationContextRunner()
        .withUserConfiguration(GuardOnly::class.java)
        .withPropertyValues("app.auth.allowed-emails=me@test.local")

    @Test
    fun `prod 프로필에서 개발 로그인을 켜면 기동이 거부된다`() {
        runner
            .withPropertyValues("spring.profiles.active=prod", "app.auth.dev-login.enabled=true")
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).rootCause()
                    .hasMessageContaining("prod 프로필에서는 app.auth.dev-login.enabled 를 켤 수 없습니다")
            }
    }

    @Test
    fun `prod 가 아니면 개발 로그인을 켜도 뜬다`() {
        runner
            .withPropertyValues("spring.profiles.active=local", "app.auth.dev-login.enabled=true")
            .run { context -> assertThat(context).hasNotFailed() }
    }

    @Test
    fun `prod 프로필에서 허용 목록이 비어 있으면 기동이 거부된다`() {
        ApplicationContextRunner()
            .withUserConfiguration(GuardOnly::class.java)
            .withPropertyValues("spring.profiles.active=prod")
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).rootCause()
                    .hasMessageContaining("allowed-emails 가 비어 있을 수 없습니다")
            }
    }

    @Test
    fun `prod 에서 허용 목록이 있고 개발 로그인이 꺼져 있으면 뜬다`() {
        runner
            .withPropertyValues("spring.profiles.active=prod")
            .run { context -> assertThat(context).hasNotFailed() }
    }

    @Test
    fun `운영자 목록이 허용 목록의 부분집합이 아니면 기동이 거부된다`() {
        runner
            .withPropertyValues("app.auth.admin-emails=boss@test.local")
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).rootCause()
                    .hasMessageContaining("admin-emails 는 app.auth.allowed-emails 에 포함돼야 합니다")
            }
    }
}
