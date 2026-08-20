package com.example.credit_system_kotlin.global.config

/**
 * Java 테스트는 쓰지 않는 하위 설정에 null을 넘겼지만,
 * Kotlin AppProperties는 모두 non-null이므로 기본값을 채워 만든다.
 */
fun appProperties(
    generation: AppProperties.Generation = AppProperties.Generation(cost = 100L, maxAttempts = 3),
    stub: AppProperties.Stub = AppProperties.Stub(failureRate = 0.0, minDelayMillis = 0, maxDelayMillis = 0),
    heartbeat: AppProperties.Heartbeat = AppProperties.Heartbeat(
        timeoutSeconds = 10, refreshIntervalSeconds = 5, suppressionAlertSeconds = 60
    ),
    processing: AppProperties.Processing = AppProperties.Processing(timeoutSeconds = 60)
): AppProperties = AppProperties(generation, stub, heartbeat, processing)
