package com.example.credit_system_kotlin.job.worker

import com.example.credit_system_kotlin.global.config.WorkerProperties
import com.example.credit_system_kotlin.job.domain.Job
import com.example.credit_system_kotlin.job.domain.JobStatus
import com.example.credit_system_kotlin.job.repository.JobRepository
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.core.task.SyncTaskExecutor
import org.springframework.core.task.TaskExecutor
import org.springframework.core.task.TaskRejectedException
import org.springframework.dao.QueryTimeoutException
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
            WorkerProperties(true, 20, CONCURRENCY), POLL_INTERVAL_MILLIS
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
    fun `선점 UPDATE가 반복 실패해도 이후 주기에서 다시 처리한다`() {
        doReturn(listOf(job)).whenever(jobRepository).findByStatusOrderByIdAsc(eq(JobStatus.HOLDING), any())
        doThrow(QueryTimeoutException("db unavailable"))
            .whenever(jobRepository).startProcessingIfAttemptMatches(eq(1L), eq(0), any<Instant>())

        repeat(CONCURRENCY + 2) { worker.dispatchPendingJobs() }

        verify(jobProcessor, never()).runGeneration(job)

        doReturn(1).whenever(jobRepository).startProcessingIfAttemptMatches(eq(1L), eq(0), any<Instant>())

        worker.dispatchPendingJobs()

        verify(jobProcessor).runGeneration(job)
    }

    @Test
    fun `executor 위임과 롤백이 모두 실패해도 예외가 새어나가지 않는다`() {
        val rejectingWorker = GenerationWorker(
            jobRepository, jobProcessor,
            TaskExecutor { throw IllegalStateException("executor shutdown") },
            WorkerProperties(true, 20, CONCURRENCY), POLL_INTERVAL_MILLIS
        )
        doReturn(listOf(job)).whenever(jobRepository).findByStatusOrderByIdAsc(eq(JobStatus.HOLDING), any())
        doReturn(1).whenever(jobRepository).startProcessingIfAttemptMatches(eq(1L), eq(0), any<Instant>())
        doThrow(QueryTimeoutException("db unavailable")).whenever(jobRepository)
            .transitionIfStatusAndAttemptMatch(
                eq(1L), eq(JobStatus.HOLDING), eq(JobStatus.PROCESSING), eq(0), any<Instant>()
            )

        assertThatCode { rejectingWorker.dispatchPendingJobs() }.doesNotThrowAnyException()

        verify(jobProcessor, never()).runGeneration(job)
    }

    @Test
    fun `한 작업의 선점 실패가 같은 배치의 나머지 작업을 막지 않는다`() {
        val second = Job.hold(10L, 100L, "dog")
        ReflectionTestUtils.setField(second, "id", 2L)
        doReturn(listOf(job, second)).whenever(jobRepository).findByStatusOrderByIdAsc(eq(JobStatus.HOLDING), any())
        doThrow(QueryTimeoutException("db unavailable"))
            .whenever(jobRepository).startProcessingIfAttemptMatches(eq(1L), eq(0), any<Instant>())
        doReturn(1).whenever(jobRepository).startProcessingIfAttemptMatches(eq(2L), eq(0), any<Instant>())

        worker.dispatchPendingJobs()

        verify(jobProcessor).runGeneration(second)
    }

    @Test
    fun `executor가 거부하면 선점을 롤백하고 이번 주기를 중단한다`() {
        val second = Job.hold(10L, 100L, "dog")
        ReflectionTestUtils.setField(second, "id", 2L)
        val rejectingWorker = GenerationWorker(
            jobRepository, jobProcessor,
            TaskExecutor { throw TaskRejectedException("pool exhausted") },
            WorkerProperties(true, 20, CONCURRENCY), POLL_INTERVAL_MILLIS
        )
        doReturn(listOf(job, second)).whenever(jobRepository).findByStatusOrderByIdAsc(eq(JobStatus.HOLDING), any())
        doReturn(1).whenever(jobRepository).startProcessingIfAttemptMatches(eq(1L), eq(0), any<Instant>())

        rejectingWorker.dispatchPendingJobs()

        verify(jobRepository).transitionIfStatusAndAttemptMatch(
            eq(1L), eq(JobStatus.HOLDING), eq(JobStatus.PROCESSING), eq(0), any<Instant>()
        )
        verify(jobRepository, never()).startProcessingIfAttemptMatches(eq(2L), any<Int>(), any<Instant>())
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
        private const val POLL_INTERVAL_MILLIS = 500L
    }
}
