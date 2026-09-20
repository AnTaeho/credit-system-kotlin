package com.example.credit_system_kotlin.support

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender

/**
 * MDC 를 **로그를 찍은 스레드에서** 붙잡아 두는 [ListAppender].
 *
 * 그냥 [ListAppender] 를 쓰면 MDC 단언이 조용히 거짓말을 한다. logback 의 `LoggingEvent` 는
 * `mdcPropertyMap` 을 **처음 읽을 때** 비로소 채우는데(그 전까지 null), 그 "처음"이 테스트 스레드에서
 * `finally` 로 MDC 를 지운 뒤라면 빈 맵이 잡힌다. 그러면 "jobId 가 들어갔다" 단언은 실패하고,
 * 반대로 "누수가 없다" 단언은 아무것도 검증하지 않은 채 통과한다.
 *
 * [ch.qos.logback.classic.spi.ILoggingEvent.prepareForDeferredProcessing] 이 그 읽기를 지금 강제한다.
 * 비동기 appender 가 쓰라고 있는 훅을 같은 목적으로 쓴다.
 */
class MdcSnapshotAppender : ListAppender<ILoggingEvent>() {
    override fun append(eventObject: ILoggingEvent) {
        eventObject.prepareForDeferredProcessing()
        super.append(eventObject)
    }
}
