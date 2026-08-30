package com.example.credit_system_kotlin.global.config

/**
 * Java 테스트는 쓰지 않는 하위 설정에 null을 넘겼지만,
 * Kotlin AppProperties는 모두 non-null이므로 기본값을 채워 만든다.
 */
fun appProperties(
    generation: AppProperties.Generation = AppProperties.Generation(cost = 100L),
    stub: AppProperties.Stub = AppProperties.Stub(failureRate = 0.0, minDelayMillis = 0, maxDelayMillis = 0)
): AppProperties = AppProperties(generation, stub)
