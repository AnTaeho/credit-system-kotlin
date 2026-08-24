package com.example.credit_system_kotlin.job.worker

import com.example.credit_system_kotlin.global.exception.StubGenerationException
import com.example.credit_system_kotlin.heartbeat.HeartbeatRegistry
import com.example.credit_system_kotlin.job.domain.Job
import com.example.credit_system_kotlin.job.service.JobLifecycleService
import com.example.credit_system_kotlin.job.stub.GenerationStubClient
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.QueryTimeoutException
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.transaction.CannotCreateTransactionException
import java.util.concurrent.ScheduledFuture

@ExtendWith(MockitoExtension::class)
class GenerationJobProcessorTest {

    @Mock lateinit var heartbeatRegistry: HeartbeatRegistry

    @Mock lateinit var stubClient: GenerationStubClient

    @Mock lateinit var jobLifecycleService: JobLifecycleService

    @Mock lateinit var heartbeatFuture: ScheduledFuture<*>

    private lateinit var processor: GenerationJobProcessor
    private lateinit var job: Job

    @BeforeEach
    fun setUp() {
        processor = GenerationJobProcessor(heartbeatRegistry, stubClient, jobLifecycleService)
        job = Job.hold(10L, 100L, "cat")
        ReflectionTestUtils.setField(job, "id", 1L)
        doReturn(heartbeatFuture).whenever(heartbeatRegistry).startHeartbeat(1L, 0)
    }

    @Test
    fun `성공하면 confirm하고 heartbeat를 정리한다`() {
        whenever(stubClient.generate("cat")).thenReturn("https://example.test/cat.png")

        processor.runGeneration(job)

        verify(jobLifecycleService).confirm(job, "https://example.test/cat.png")
        verify(heartbeatRegistry).stopHeartbeat(1L, 0, heartbeatFuture)
    }

    @Test
    fun `생성 실패는 FAILED로 기록하고 heartbeat를 정리한다`() {
        whenever(stubClient.generate("cat")).thenThrow(StubGenerationException("cat"))

        processor.runGeneration(job)

        verify(jobLifecycleService).markFailed(1L, 0)
        verify(jobLifecycleService, never()).confirm(job, "https://example.test/cat.png")
        verify(heartbeatRegistry).stopHeartbeat(1L, 0, heartbeatFuture)
    }

    @Test
    fun `예기치 못한 런타임예외도 FAILED로 기록하고 heartbeat를 정리한다`() {
        whenever(stubClient.generate("cat")).thenThrow(IllegalStateException("interrupted"))

        processor.runGeneration(job)

        verify(jobLifecycleService).markFailed(1L, 0)
        verify(jobLifecycleService, never()).confirm(job, "https://example.test/cat.png")
        verify(heartbeatRegistry).stopHeartbeat(1L, 0, heartbeatFuture)
    }

    @Test
    fun `결과 반영이 실패해도 재시도가 성공하면 결과를 살린다`() {
        whenever(stubClient.generate("cat")).thenReturn("https://example.test/cat.png")
        doThrow(CannotCreateTransactionException("connection pool exhausted"))
            .doNothing()
            .whenever(jobLifecycleService).confirm(job, "https://example.test/cat.png")

        processor.runGeneration(job)

        verify(jobLifecycleService, times(2)).confirm(job, "https://example.test/cat.png")
        verify(jobLifecycleService, never()).markFailed(1L, 0)
        verify(heartbeatRegistry).stopHeartbeat(1L, 0, heartbeatFuture)
    }

    @Test
    fun `결과 반영 재시도를 모두 소진하면 FAILED로 바꾸지 않고 PROCESSING을 유지한다`() {
        whenever(stubClient.generate("cat")).thenReturn("https://example.test/cat.png")
        doThrow(QueryTimeoutException("lock wait timeout"))
            .whenever(jobLifecycleService).confirm(job, "https://example.test/cat.png")

        processor.runGeneration(job)

        verify(jobLifecycleService, times(3)).confirm(job, "https://example.test/cat.png")
        verify(jobLifecycleService, never()).markFailed(1L, 0)
        verify(heartbeatRegistry).stopHeartbeat(1L, 0, heartbeatFuture)
    }

    @Test
    fun `결과 반영 실패가 재시도 대상이 아니면 즉시 포기하고 PROCESSING을 유지한다`() {
        whenever(stubClient.generate("cat")).thenReturn("https://example.test/cat.png")
        doThrow(DataIntegrityViolationException("constraint violation"))
            .whenever(jobLifecycleService).confirm(job, "https://example.test/cat.png")

        processor.runGeneration(job)

        verify(jobLifecycleService, times(1)).confirm(job, "https://example.test/cat.png")
        verify(jobLifecycleService, never()).markFailed(1L, 0)
        verify(heartbeatRegistry).stopHeartbeat(1L, 0, heartbeatFuture)
    }
}
