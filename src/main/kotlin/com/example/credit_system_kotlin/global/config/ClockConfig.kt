package com.example.credit_system_kotlin.global.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/** 테스트가 고정 시계로 갈아끼울 수 있게 빈으로 둔다. */
@Configuration
class ClockConfig {

    @Bean
    fun clock(): Clock = Clock.systemUTC()
}
