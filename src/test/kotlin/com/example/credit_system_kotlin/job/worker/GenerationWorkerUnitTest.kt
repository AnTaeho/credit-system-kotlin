package com.example.credit_system_kotlin.job.worker

import com.example.credit_system_kotlin.global.config.WorkerProperties
import com.example.credit_system_kotlin.job.domain.Job
import com.example.credit_system_kotlin.job.domain.JobStatus
import com.example.credit_system_kotlin.job.repository.JobRepository
import com.example.credit_system_kotlin.job.service.JobLifecycleService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.whenever
import org.springframework.test.util.ReflectionTestUtils

@ExtendWith(MockitoExtension::class)
class GenerationWorkerUnitTest {

    @Mock lateinit var jobRepository: JobRepository

    @Mock lateinit var jobProcessor: GenerationJobProcessor

    @Mock lateinit var jobLifecycleService: JobLifecycleService

    private lateinit var worker: GenerationWorker
    private lateinit var job: Job

    @BeforeEach
    fun setUp() {
        worker = GenerationWorker(jobRepository, jobProcessor, jobLifecycleService, WorkerProperties(true, 20))
        job = Job.hold(10L, 100L, "cat")
        ReflectionTestUtils.setField(job, "id", 1L)
    }

    @Test
    fun `HOLDING 작업을 배치 크기만큼 읽어 순서대로 처리한다`() {
        val second = Job.hold(10L, 100L, "dog")
        ReflectionTestUtils.setField(second, "id", 2L)
        whenever(jobRepository.findByStatusOrderByIdAsc(eq(JobStatus.HOLDING), any()))
            .thenReturn(listOf(job, second))

        worker.dispatchPendingJobs()

        val inOrder = inOrder(jobLifecycleService, jobProcessor)
        inOrder.verify(jobLifecycleService).startProcessing(1L)
        inOrder.verify(jobProcessor).runGeneration(job)
        inOrder.verify(jobLifecycleService).startProcessing(2L)
        inOrder.verify(jobProcessor).runGeneration(second)
    }
}
