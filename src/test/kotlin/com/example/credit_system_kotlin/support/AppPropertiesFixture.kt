package com.example.credit_system_kotlin.support

import com.example.credit_system_kotlin.global.config.AppProperties

fun appProperties(
    generation: AppProperties.Generation = AppProperties.Generation(cost = 100L, maxAttempts = 3),
    stub: AppProperties.Stub = AppProperties.Stub(failureRate = 0.0, minDelayMillis = 0, maxDelayMillis = 0),
    processing: AppProperties.Processing = AppProperties.Processing(timeoutSeconds = 60),
    idempotency: AppProperties.Idempotency = AppProperties.Idempotency(retentionDays = 7),
    admin: AppProperties.Admin = AppProperties.Admin()
): AppProperties = AppProperties(generation, stub, processing, idempotency, admin)
