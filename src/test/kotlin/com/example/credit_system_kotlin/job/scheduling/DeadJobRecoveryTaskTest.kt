package com.example.credit_system_kotlin.job.scheduling

import com.example.credit_system_kotlin.global.config.AppProperties
import com.example.credit_system_kotlin.global.config.appProperties
import com.example.credit_system_kotlin.heartbeat.HeartbeatRegistry
import com.example.credit_system_kotlin.heartbeat.JobAttempt
import com.example.credit_system_kotlin.job.domain.Job
import com.example.credit_system_kotlin.job.domain.JobStatus
import com.example.credit_system_kotlin.job.event.RecoveryDetector
import com.example.credit_system_kotlin.job.repository.JobRepository
import com.example.credit_system_kotlin.job.service.JobLifecycleService
import com.example.credit_system_kotlin.support.RecordingEventPublisher
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.data.domain.Pageable
import org.springframework.test.util.ReflectionTestUtils
import java.time.Instant

@ExtendWith(MockitoExtension::class)
class DeadJobRecoveryTaskTest {

    @Mock lateinit var heartbeatRegistry: HeartbeatRegistry

    @Mock lateinit var jobRepository: JobRepository

    @Mock lateinit var jobLifecycleService: JobLifecycleService

    private lateinit var task: DeadJobRecoveryTask

    private val eventPublisher = RecordingEventPublisher()

    @BeforeEach
    fun setUp() {
        eventPublisher.clear()
        task = DeadJobRecoveryTask(
            heartbeatRegistry, jobRepository, jobLifecycleService,
            appProperties(processing = AppProperties.Processing(timeoutSeconds = 60)),
            eventPublisher
        )
        whenever(heartbeatRegistry.findExpiredAttempts()).thenReturn(emptySet())
    }

    private fun staleProcessingJob(id: Long): Job {
        val job = Job.hold(1L, 100L, "cat")
        ReflectionTestUtils.setField(job, "id", id)
        ReflectionTestUtils.setField(job, "status", JobStatus.PROCESSING)
        return job
    }

    private fun failedJob(id: Long, attemptNo: Int): Job {
        val job = Job.hold(1L, 100L, "cat")
        ReflectionTestUtils.setField(job, "id", id)
        ReflectionTestUtils.setField(job, "status", JobStatus.FAILED)
        ReflectionTestUtils.setField(job, "attemptNo", attemptNo)
        return job
    }

    @Test
    fun `정체된 PROCESSING job은 heartbeat가 없으면 FAILED로 전이한다`() {
        val job = staleProcessingJob(20L)
        whenever(
            jobRepository.findByStatusAndUpdatedAtBeforeOrderByIdAsc(
                eq(JobStatus.PROCESSING), any<Instant>(), any<Pageable>()
            )
        ).thenReturn(listOf(job))
        whenever(heartbeatRegistry.hasLiveHeartbeat(20L, 0)).thenReturn(false)
        whenever(
            jobRepository.failIfProcessing(eq(20L), any(), any<Instant>())
        ).thenReturn(1)

        task.scan()

        verify(jobRepository).failIfProcessing(eq(20L), eq(0), any<Instant>())
        verify(heartbeatRegistry).removeHeartbeat(20L, 0)
    }

    @Test
    fun `정체된 PROCESSING job이라도 live heartbeat가 있으면 회수하지 않는다`() {
        val job = staleProcessingJob(21L)
        whenever(
            jobRepository.findByStatusAndUpdatedAtBeforeOrderByIdAsc(
                eq(JobStatus.PROCESSING), any<Instant>(), any<Pageable>()
            )
        ).thenReturn(listOf(job))
        whenever(heartbeatRegistry.hasLiveHeartbeat(21L, 0)).thenReturn(true)

        task.scan()

        verify(jobRepository, never()).failIfProcessing(eq(21L), any(), any<Instant>())
    }

    @Test
    fun `만료 회수는 findById 재조회 없이 heartbeat가 알려준 attemptNo로 전이한다`() {
        whenever(heartbeatRegistry.findExpiredAttempts()).thenReturn(setOf(JobAttempt(31L, 3)))
        whenever(
            jobRepository.failIfProcessing(eq(31L), eq(3), any<Instant>())
        ).thenReturn(1)

        task.scan()

        verify(jobRepository).failIfProcessing(eq(31L), eq(3), any<Instant>())
        verify(heartbeatRegistry).removeHeartbeat(31L, 3)
        verify(jobRepository, never()).findById(any())
    }

    @Test
    fun `실패한 작업이 최대 attempt 미만이면 재시도한다`() {
        val job = failedJob(40L, 1)
        whenever(jobRepository.findByStatusOrderByIdAsc(eq(JobStatus.FAILED), any<Pageable>()))
            .thenReturn(listOf(job))

        task.scan()

        verify(jobLifecycleService).retry(job)
        verify(jobLifecycleService, never()).finalRefund(any())
    }

    @Test
    fun `실패한 작업이 최대 attempt에 도달하면 환불한다`() {
        val job = failedJob(41L, 2)
        whenever(jobRepository.findByStatusOrderByIdAsc(eq(JobStatus.FAILED), any<Pageable>()))
            .thenReturn(listOf(job))

        task.scan()

        verify(jobLifecycleService).finalRefund(job)
        verify(jobLifecycleService, never()).retry(any())
    }

    @Test
    fun `FAILED 스냅샷을 그대로 넘기고 최신 상태 판정은 조건부 UPDATE에 맡긴다`() {
        val staleSnapshot = failedJob(42L, 2)
        whenever(jobRepository.findByStatusOrderByIdAsc(eq(JobStatus.FAILED), any<Pageable>()))
            .thenReturn(listOf(staleSnapshot))

        task.scan()

        val jobCaptor = argumentCaptor<Job>()
        verify(jobLifecycleService).finalRefund(jobCaptor.capture())
        assertThat(jobCaptor.firstValue).isSameAs(staleSnapshot)
        verify(jobLifecycleService, never()).retry(any())
    }

    @Test
    fun `한 job의 환불 실패가 같은 주기의 나머지 job을 막지 않는다`() {
        val job1 = failedJob(1L, 2)
        val job2 = failedJob(2L, 2)
        whenever(jobRepository.findByStatusOrderByIdAsc(eq(JobStatus.FAILED), any<Pageable>()))
            .thenReturn(listOf(job1, job2))
        doThrow(IllegalStateException("조직 행 없음")).whenever(jobLifecycleService).finalRefund(job1)

        task.scan()

        verify(jobLifecycleService).finalRefund(job2)
    }

    @Test
    fun `heartbeat 만료 회수 실패가 나머지 만료 job을 막지 않는다`() {
        val expiredAttempts = linkedSetOf(JobAttempt(1L, 0), JobAttempt(2L, 0))
        whenever(heartbeatRegistry.findExpiredAttempts()).thenReturn(expiredAttempts)
        whenever(
            jobRepository.failIfProcessing(eq(1L), eq(0), any<Instant>())
        ).thenThrow(RuntimeException("DB 오류"))
        whenever(
            jobRepository.failIfProcessing(eq(2L), eq(0), any<Instant>())
        ).thenReturn(1)

        task.scan()

        verify(jobRepository).failIfProcessing(eq(2L), eq(0), any<Instant>())
        verify(heartbeatRegistry).removeHeartbeat(2L, 0)
        verify(heartbeatRegistry, never()).removeHeartbeat(1L, 0)
    }

    @Test
    fun `한 단계의 실패가 다음 단계를 막지 않는다`() {
        doThrow(RuntimeException("PROCESSING 조회 실패"))
            .whenever(jobRepository).findByStatusAndUpdatedAtBeforeOrderByIdAsc(
                eq(JobStatus.PROCESSING), any<Instant>(), any<Pageable>()
            )
        val job = failedJob(50L, 1)
        whenever(jobRepository.findByStatusOrderByIdAsc(eq(JobStatus.FAILED), any<Pageable>()))
            .thenReturn(listOf(job))

        task.scan()

        verify(jobLifecycleService).retry(job)
    }

    @Test
    fun `FAILED job은 배치 크기만큼만 조회한다`() {
        task.scan()

        val pageableCaptor = argumentCaptor<Pageable>()
        verify(jobRepository).findByStatusOrderByIdAsc(eq(JobStatus.FAILED), pageableCaptor.capture())
        assertThat(pageableCaptor.firstValue.pageSize).isEqualTo(100)
    }

    @Test
    fun `PROCESSING 정체 조회도 배치 크기만큼만 가져온다`() {
        task.scan()

        val pageableCaptor = argumentCaptor<Pageable>()
        verify(jobRepository).findByStatusAndUpdatedAtBeforeOrderByIdAsc(
            eq(JobStatus.PROCESSING), any<Instant>(), pageableCaptor.capture()
        )
        assertThat(pageableCaptor.firstValue.pageSize).isEqualTo(100)
    }

    @Test
    fun `heartbeat 만료 회수는 HEARTBEAT detector로 JobRecovered를 발행한다`() {
        whenever(heartbeatRegistry.findExpiredAttempts()).thenReturn(setOf(JobAttempt(60L, 2)))
        whenever(jobRepository.failIfProcessing(eq(60L), eq(2), any<Instant>())).thenReturn(1)

        task.scan()

        assertThat(eventPublisher.recoveryEvents())
            .singleElement()
            .satisfies({
                assertThat(it.detector).isEqualTo(RecoveryDetector.HEARTBEAT)
                assertThat(it.jobId).isEqualTo(60L)
                assertThat(it.attemptNo).isEqualTo(2)
            })
    }

    @Test
    fun `updatedAt 정체 회수는 BACKSTOP detector로 JobRecovered를 발행한다`() {
        val job = staleProcessingJob(61L)
        whenever(
            jobRepository.findByStatusAndUpdatedAtBeforeOrderByIdAsc(
                eq(JobStatus.PROCESSING), any<Instant>(), any<Pageable>()
            )
        ).thenReturn(listOf(job))
        whenever(heartbeatRegistry.hasLiveHeartbeat(61L, 0)).thenReturn(false)
        whenever(jobRepository.failIfProcessing(eq(61L), eq(0), any<Instant>())).thenReturn(1)

        task.scan()

        assertThat(eventPublisher.recoveryEvents())
            .singleElement()
            .satisfies({ assertThat(it.detector).isEqualTo(RecoveryDetector.BACKSTOP) })
    }

    @Test
    fun `회수 UPDATE가 0행이면 JobRecovered를 발행하지 않는다`() {
        whenever(heartbeatRegistry.findExpiredAttempts()).thenReturn(setOf(JobAttempt(62L, 0)))
        val job = staleProcessingJob(63L)
        whenever(
            jobRepository.findByStatusAndUpdatedAtBeforeOrderByIdAsc(
                eq(JobStatus.PROCESSING), any<Instant>(), any<Pageable>()
            )
        ).thenReturn(listOf(job))
        whenever(heartbeatRegistry.hasLiveHeartbeat(63L, 0)).thenReturn(false)
        // failIfProcessing 은 스텁하지 않는다 — mock 의 Int 기본값 0이 곧 "회수 실패"다.

        task.scan()

        assertThat(eventPublisher.recoveryEvents()).isEmpty()
    }
}
