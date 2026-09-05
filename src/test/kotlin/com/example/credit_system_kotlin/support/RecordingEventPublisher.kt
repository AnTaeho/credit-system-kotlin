package com.example.credit_system_kotlin.support

import com.example.credit_system_kotlin.global.event.DefenseOutcome
import com.example.credit_system_kotlin.global.event.DefensePoint
import com.example.credit_system_kotlin.global.event.DefenseTriggered
import com.example.credit_system_kotlin.job.event.JobRecovered
import org.springframework.context.ApplicationEventPublisher
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 발행된 이벤트를 그대로 모아 두는 테스트용 [ApplicationEventPublisher].
 *
 * mock 을 쓰고 매번 `argumentCaptor` 를 세우는 것보다, 단언이 "무엇이 발행됐나"를
 * 그대로 읽게 하는 편이 짧다. 여러 테스트에서 반복되므로 하나로 묶었다.
 */
class RecordingEventPublisher : ApplicationEventPublisher {

    val events: MutableList<Any> = CopyOnWriteArrayList()

    override fun publishEvent(event: Any) {
        events += event
    }

    fun defenseEvents(): List<DefenseTriggered> = events.filterIsInstance<DefenseTriggered>()

    fun recoveryEvents(): List<JobRecovered> = events.filterIsInstance<JobRecovered>()

    fun countOf(point: DefensePoint, outcome: DefenseOutcome): Int =
        defenseEvents().count { it.point == point && it.outcome == outcome }

    fun clear() {
        events.clear()
    }
}
