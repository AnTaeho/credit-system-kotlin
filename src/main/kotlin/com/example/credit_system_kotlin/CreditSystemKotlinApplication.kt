package com.example.credit_system_kotlin

import com.example.credit_system_kotlin.global.config.AppProperties
import com.example.credit_system_kotlin.heartbeat.HeartbeatProperties
import com.example.credit_system_kotlin.job.scheduling.IdempotencyProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@EnableScheduling
@EnableConfigurationProperties(AppProperties::class, IdempotencyProperties::class, HeartbeatProperties::class)
@SpringBootApplication
class CreditSystemKotlinApplication

fun main(args: Array<String>) {
    runApplication<CreditSystemKotlinApplication>(*args)
}
