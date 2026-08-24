package com.example.credit_system_kotlin.heartbeat

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

internal class MutableClock(instant: Instant) : Clock() {

    @Volatile
    private var currentInstant: Instant = instant

    fun advance(duration: Duration) {
        currentInstant = currentInstant.plus(duration)
    }

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    override fun instant(): Instant = currentInstant
}
