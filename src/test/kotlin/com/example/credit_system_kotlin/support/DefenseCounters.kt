package com.example.credit_system_kotlin.support

import com.example.credit_system_kotlin.observability.DefenseMetrics
import io.micrometer.core.instrument.MeterRegistry

/**
 * 방어 카운터(`credit.defense`)를 읽는 테스트용 도우미.
 * [MeterRegistry] 에는 같은 컨텍스트의 다른 테스트가 올린 값이 남아 있어 [snapshot] 뒤의 [delta] 만 단언한다.
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
