package com.example.credit_system_kotlin.job.worker

import com.example.credit_system_kotlin.job.domain.Job
import com.example.credit_system_kotlin.job.service.JobLifecycleService
import com.example.credit_system_kotlin.job.stub.GenerationStubClient
import com.example.credit_system_kotlin.job.stub.StubGenerationException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.test.util.ReflectionTestUtils

@ExtendWith(MockitoExtension::class)
class GenerationJobProcessorTest {

    @Mock lateinit var stubClient: GenerationStubClient

    @Mock lateinit var jobLifecycleService: JobLifecycleService

    private lateinit var processor: GenerationJobProcessor
    private lateinit var job: Job

    @BeforeEach
    fun setUp() {
        processor = GenerationJobProcessor(stubClient, jobLifecycleService)
        job = Job.hold(10L, 100L, "cat")
        ReflectionTestUtils.setField(job, "id", 1L)
    }

    @Test
    fun `성공하면 confirm한다`() {
        whenever(stubClient.generate("cat")).thenReturn("https://example.test/cat.png")

        processor.runGeneration(job)

        verify(jobLifecycleService).confirm(job, "https://example.test/cat.png")
    }

    @Test
    fun `생성 실패는 FAILED로 기록한다`() {
        whenever(stubClient.generate("cat")).thenThrow(StubGenerationException("cat"))

        processor.runGeneration(job)

        verify(jobLifecycleService).markFailed(1L, 0)
        verify(jobLifecycleService, never()).confirm(job, "https://example.test/cat.png")
    }
}
