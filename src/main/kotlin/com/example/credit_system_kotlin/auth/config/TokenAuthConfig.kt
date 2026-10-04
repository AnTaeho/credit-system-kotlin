package com.example.credit_system_kotlin.auth.config

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder

/** 이메일·비밀번호 로그인과 토큰 발급이 쓰는 빈. 보안 규칙([SecurityConfig])과는 따로 둔다. */
@Configuration
@EnableConfigurationProperties(JwtProperties::class)
class TokenAuthConfig {

    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()
}
