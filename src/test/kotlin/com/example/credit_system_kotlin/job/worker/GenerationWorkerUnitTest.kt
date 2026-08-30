package com.example.credit_system_kotlin.job.worker

import com.example.credit_system_kotlin.global.config.WorkerProperties
import com.example.credit_system_kotlin.job.domain.Job
import com.example.credit_system_kotlin.job.domain.JobStatus
import com.example.credit_system_kotlin.job.repository.JobRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.core.task.SyncTaskExecutor
import org.springframework.test.util.ReflectionTestUtils
import java.time.Instant

@ExtendWith(MockitoExtension::class)
class GenerationWorkerUnitTest {

    @Mock lateinit var jobRepository: JobRepository

    @Mock lateinit var jobProcessor: GenerationJobProcessor

    private lateinit var worker: GenerationWorker
    private lateinit var job: Job

    @BeforeEach
    fun setUp() {
        worker = GenerationWorker(
            jobRepository, jobProcessor, SyncTaskExecutor(),
            WorkerProperties(true, 20, CONCURRENCY)
        )
        job = Job.hold(10L, 100L, "cat")
        ReflectionTestUtils.setField(job, "id", 1L)
    }

    @Test
    fun `대기 작업을 DB에서 찾아 선점한 뒤 처리기로 넘긴다`() {
        whenever(jobRepository.findByStatusOrderByIdAsc(eq(JobStatus.HOLDING), any()))
            .thenReturn(listOf(job))
        whenever(jobRepository.startProcessingIfAttemptMatches(eq(1L), eq(0), any<Instant>()))
            .thenReturn(1)

        worker.dispatchPendingJobs()

        verify(jobProcessor).runGeneration(job)
    }

    @Test
    fun `다른 워커가 선점한 작업은 외부 처리기로 넘기지 않는다`() {
        doReturn(listOf(job)).whenever(jobRepository).findByStatusOrderByIdAsc(eq(JobStatus.HOLDING), any())
        doReturn(0).whenever(jobRepository).startProcessingIfAttemptMatches(eq(1L), eq(0), any<Instant>())

        worker.dispatchPendingJobs()

        verify(jobProcessor, never()).runGeneration(job)
    }

    @Test
    fun `dispatch에 성공하면 같은 배치의 다음 작업도 처리한다`() {
        val second = Job.hold(10L, 100L, "dog")
        ReflectionTestUtils.setField(second, "id", 2L)
        doReturn(listOf(job, second)).whenever(jobRepository).findByStatusOrderByIdAsc(eq(JobStatus.HOLDING), any())
        doReturn(1).whenever(jobRepository).startProcessingIfAttemptMatches(eq(1L), eq(0), any<Instant>())
        doReturn(1).whenever(jobRepository).startProcessingIfAttemptMatches(eq(2L), eq(0), any<Instant>())

        worker.dispatchPendingJobs()

        verify(jobProcessor).runGeneration(job)
        verify(jobProcessor).runGeneration(second)
    }

    companion object {
        private const val CONCURRENCY = 3
    }
}
