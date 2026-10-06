package com.example.credit_system_kotlin.support

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** 앞으로 당길 수 있는 고정 시계다. `Clock.fixed` 는 인스턴트를 바꿀 수 없어 경과 시간을 재는 지표를 검증할 수 없다. */
class FixedMutableClock(private var instant: Instant) : Clock() {

    fun advance(duration: Duration) {
        instant = instant.plus(duration)
    }

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId?): Clock = this

    override fun instant(): Instant = instant
}
