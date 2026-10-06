package com.example.credit_system_kotlin.support

import com.example.credit_system_kotlin.global.event.DefenseOutcome
import com.example.credit_system_kotlin.global.event.DefensePoint
import com.example.credit_system_kotlin.global.event.DefenseTriggered
import com.example.credit_system_kotlin.job.event.JobRecovered
import org.springframework.context.ApplicationEventPublisher
import java.util.concurrent.CopyOnWriteArrayList

/** 발행된 이벤트를 그대로 모아 두는 테스트용 [ApplicationEventPublisher]. */
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
