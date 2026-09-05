package com.example.credit_system_kotlin.global.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * `Clock` 을 빈으로 노출해 코드가 `Instant.now()` 대신 주입받은 시계를 쓸 수 있게 한다.
 * 테스트에서 `Clock.fixed(...)` 로 갈아끼워 시간 의존 로직(예: staleness 게이지)을
 * 결정적으로 검증하기 위한 것이다.
 */
@Configuration
class ClockConfig {

    @Bean
    fun clock(): Clock = Clock.systemUTC()
}
