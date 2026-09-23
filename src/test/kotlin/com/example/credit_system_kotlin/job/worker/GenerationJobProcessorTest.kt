package com.example.credit_system_kotlin.job.worker

import com.example.credit_system_kotlin.heartbeat.HeartbeatRegistry
import com.example.credit_system_kotlin.job.domain.Job
import com.example.credit_system_kotlin.job.event.ExternalGenerationCalled
import com.example.credit_system_kotlin.job.generation.GenerationClient
import com.example.credit_system_kotlin.job.generation.GenerationTimeoutException
import com.example.credit_system_kotlin.job.generation.stub.StubGenerationException
import com.example.credit_system_kotlin.job.service.JobLifecycleService
import com.example.credit_system_kotlin.support.RecordingEventPublisher
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.test.util.ReflectionTestUtils
import java.util.concurrent.ScheduledFuture

@ExtendWith(MockitoExtension::class)
class GenerationJobProcessorTest {

    @Mock lateinit var heartbeatRegistry: HeartbeatRegistry

    @Mock lateinit var generationClient: GenerationClient

    @Mock lateinit var jobLifecycleService: JobLifecycleService

    @Mock lateinit var heartbeatFuture: ScheduledFuture<*>

    private lateinit var processor: GenerationJobProcessor
    private lateinit var job: Job
    private lateinit var eventPublisher: RecordingEventPublisher

    private fun externalCallEvents(): List<ExternalGenerationCalled> =
        eventPublisher.events.filterIsInstance<ExternalGenerationCalled>()

    @BeforeEach
    fun setUp() {
        eventPublisher = RecordingEventPublisher()
        processor = GenerationJobProcessor(
            heartbeatRegistry, generationClient, jobLifecycleService, eventPublisher
        )
        job = Job.hold(10L, 100L, "cat")
        ReflectionTestUtils.setField(job, "id", 1L)
        doReturn(heartbeatFuture).whenever(heartbeatRegistry).startHeartbeat(1L, 0)
    }

    @Test
    fun `성공하면 confirm하고 heartbeat를 정리한다`() {
        whenever(generationClient.generate("cat")).thenReturn("https://example.test/cat.png")

        processor.runGeneration(job)

        verify(jobLifecycleService).confirm(job, "https://example.test/cat.png")
        verify(heartbeatRegistry).stopHeartbeat(1L, 0, heartbeatFuture)
    }

    /**
     * INV-04b 계측이 붙어 있는 자리를 못 박는다. 이벤트가 발행되지 않으면
     * `credit.generation.external.calls` 가 조용히 0으로 남아, 지표가 사라진 것을 아무도 모른다.
     */
    @Test
    fun `외부 호출 직전에 INV-04b 이벤트를 발행한다`() {
        whenever(generationClient.generate("cat")).thenReturn("https://example.test/cat.png")

        processor.runGeneration(job)

        assertThat(externalCallEvents()).containsExactly(ExternalGenerationCalled(1L, 0))
    }

    /**
     * 호출 **직전**에 발행하므로, 호출이 던져도 이벤트는 그대로 하나다.
     * 실패한 호출도 외부로는 나갔을 수 있고, 그게 중복 원가의 본체다.
     */
    @Test
    fun `생성이 실패해도 INV-04b 이벤트는 그대로 하나 발행된다`() {
        whenever(generationClient.generate("cat")).thenThrow(StubGenerationException("cat"))

        processor.runGeneration(job)

        assertThat(externalCallEvents()).containsExactly(ExternalGenerationCalled(1L, 0))
    }

    @Test
    fun `생성 실패는 FAILED로 기록하고 heartbeat를 정리한다`() {
        whenever(generationClient.generate("cat")).thenThrow(StubGenerationException("cat"))

        processor.runGeneration(job)

        verify(jobLifecycleService).markFailed(1L, 0)
        verify(jobLifecycleService, never()).confirm(job, "https://example.test/cat.png")
        verify(heartbeatRegistry).stopHeartbeat(1L, 0, heartbeatFuture)
    }

    @Test
    fun `생성 타임아웃도 FAILED로 기록하고 heartbeat를 정리한다`() {
        whenever(generationClient.generate("cat")).thenThrow(GenerationTimeoutException("cat", 1000L))

        processor.runGeneration(job)

        verify(jobLifecycleService).markFailed(1L, 0)
        verify(jobLifecycleService, never()).confirm(job, "https://example.test/cat.png")
        verify(heartbeatRegistry).stopHeartbeat(1L, 0, heartbeatFuture)
    }

    @Test
    fun `예기치 못한 런타임예외도 FAILED로 기록하고 heartbeat를 정리한다`() {
        whenever(generationClient.generate("cat")).thenThrow(IllegalStateException("interrupted"))

        processor.runGeneration(job)

        verify(jobLifecycleService).markFailed(1L, 0)
        verify(jobLifecycleService, never()).confirm(job, "https://example.test/cat.png")
        verify(heartbeatRegistry).stopHeartbeat(1L, 0, heartbeatFuture)
    }

    @Test
    fun `결과 반영이 실패해도 FAILED로 바꾸지 않고 PROCESSING을 유지한다`() {
        whenever(generationClient.generate("cat")).thenReturn("https://example.test/cat.png")
        doThrow(DataIntegrityViolationException("constraint violation"))
            .whenever(jobLifecycleService).confirm(job, "https://example.test/cat.png")

        assertThatCode { processor.runGeneration(job) }.doesNotThrowAnyException()

        verify(jobLifecycleService, never()).markFailed(1L, 0)
        verify(heartbeatRegistry).stopHeartbeat(1L, 0, heartbeatFuture)
    }
}
