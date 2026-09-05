package com.example.credit_system_kotlin.support

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * 앞으로 당길 수 있는 고정 시계다.
 *
 * `Clock.fixed` 는 인스턴트를 바꿀 수 없어서 "시간이 흐른 뒤"를 흉내 낼 수 없다.
 * staleness 게이지처럼 경과 시간을 재는 지표를 결정적으로 검증하려면 시계를 직접 밀어야 한다.
 */
class FixedMutableClock(private var instant: Instant) : Clock() {

    fun advance(duration: Duration) {
        instant = instant.plus(duration)
    }

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId?): Clock = this

    override fun instant(): Instant = instant
}
