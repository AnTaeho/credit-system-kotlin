package com.example.credit_system_kotlin.support

import com.example.credit_system_kotlin.observability.DefenseMetrics
import io.micrometer.core.instrument.MeterRegistry

/**
 * 실제 [MeterRegistry] 의 방어 카운터(`credit.defense`)를 읽는 테스트용 도우미.
 *
 * `MeterRegistry` 는 Spring 컨텍스트에서 싱글턴이라 같은 컨텍스트를 공유하는 다른 테스트가
 * 이미 올려 둔 값이 남아 있다. 절대값을 단언하면 실행 순서에 따라 깨지는 플레이키 테스트가
 * 되므로, 시작 시점의 값을 [snapshot] 으로 찍어 두고 [delta] 로 **증가분**만 단언한다.
 */
class DefenseCounters(private val registry: MeterRegistry) {

    fun count(point: String, outcome: String): Double =
        registry.find(DefenseMetrics.DEFENSE_METRIC)
            .tag("point", point)
            .tag("outcome", outcome)
            .counter()
            ?.count() ?: 0.0

    fun snapshot(vararg combinations: Pair<String, String>): Map<Pair<String, String>, Double> =
        combinations.associateWith { (point, outcome) -> count(point, outcome) }

    fun delta(before: Map<Pair<String, String>, Double>, point: String, outcome: String): Double =
        count(point, outcome) - (before[point to outcome] ?: 0.0)
}
