package com.example.credit_system_kotlin.global.scheduling

import com.example.credit_system_kotlin.global.event.DomainSnapshotTaken
import com.example.credit_system_kotlin.job.domain.Job
import com.example.credit_system_kotlin.job.domain.JobStatus
import com.example.credit_system_kotlin.job.repository.JobRepository
import com.example.credit_system_kotlin.ledger.domain.LedgerEntry
import com.example.credit_system_kotlin.ledger.repository.LedgerRepository
import com.example.credit_system_kotlin.organization.domain.Organization
import com.example.credit_system_kotlin.organization.repository.OrganizationRepository
import com.example.credit_system_kotlin.support.FixedMutableClock
import com.example.credit_system_kotlin.support.RecordingEventPublisher
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.util.ReflectionTestUtils
import java.time.Instant

@ActiveProfiles("test")
@DataJpaTest
class DomainSnapshotTaskTest @Autowired constructor(
    private val jobRepository: JobRepository,
    private val organizationRepository: OrganizationRepository,
    private val ledgerRepository: LedgerRepository
) {

    private val fixedInstant = Instant.parse("2030-01-01T00:00:00Z")
    private val clock = FixedMutableClock(fixedInstant)
    private val eventPublisher = RecordingEventPublisher()

    private val task = DomainSnapshotTask(jobRepository, organizationRepository, eventPublisher, clock)

    @Test
    fun `job 이 없으면 모든 값이 0이고 가장 오래된 미결 나이도 0이다`() {
        task.takeSnapshot()

        val snapshot = publishedSnapshot()
        assertThat(snapshot.outstandingHoldCount).isZero()
        assertThat(snapshot.outstandingHoldAmount).isZero()
        assertThat(snapshot.oldestPendingAgeSeconds).isZero()
        assertThat(snapshot.negativeBalanceOrgs).isZero()
        assertThat(snapshot.jobsWithoutHold).isZero()
        assertThat(snapshot.unsettledTerminalJobs).isZero()
    }

    @Test
    fun `종결된 job 은 미결에서 빠지고 HOLDING 만 남는다`() {
        holdingJob(holdAmount = 100L, createdSecondsAgo = 90L)
        completedJob()
        refundedJob()

        task.takeSnapshot()

        val snapshot = publishedSnapshot()
        assertThat(snapshot.outstandingHoldCount).isEqualTo(1)
        assertThat(snapshot.outstandingHoldAmount).isEqualTo(100)
        assertThat(snapshot.oldestPendingAgeSeconds).isEqualTo(90)
    }

    @Test
    fun `FAILED 도 미결에 포함된다`() {
        failedJob(holdAmount = 70L, createdSecondsAgo = 120L)

        task.takeSnapshot()

        val snapshot = publishedSnapshot()
        assertThat(snapshot.outstandingHoldCount).isEqualTo(1)
        assertThat(snapshot.outstandingHoldAmount).isEqualTo(70)
        assertThat(snapshot.oldestPendingAgeSeconds).isEqualTo(120)
    }

    @Test
    fun `가장 오래된 미결 나이는 재시도로 상태가 바뀌어도 createdAt 기준이다`() {
        // 이 job 은 HOLDING → PROCESSING → FAILED 로 상태가 세 번 바뀌어 updatedAt 은 지금이지만,
        // 돈은 createdAt 부터 계속 묶여 있었다.
        failedJob(holdAmount = 100L, createdSecondsAgo = 300L)
        holdingJob(holdAmount = 100L, createdSecondsAgo = 10L)

        task.takeSnapshot()

        assertThat(publishedSnapshot().oldestPendingAgeSeconds).isEqualTo(300)
    }

    @Test
    fun `잔액이 음수인 조직을 센다`() {
        organizationRepository.saveAndFlush(Organization("healthy", 1000L))
        val broken = organizationRepository.saveAndFlush(Organization("broken", 1000L))
        ReflectionTestUtils.setField(broken, "balance", -50L)
        organizationRepository.saveAndFlush(broken)

        task.takeSnapshot()

        assertThat(publishedSnapshot().negativeBalanceOrgs).isEqualTo(1)
    }

    @Test
    fun `HOLD 원장 없이 저장된 job 을 센다`() {
        jobRepository.saveAndFlush(Job.hold(ORG_ID, 100L, "cat"))
        holdingJob()

        task.takeSnapshot()

        assertThat(publishedSnapshot().jobsWithoutHold).isEqualTo(1)
    }

    @Test
    fun `COMPLETED 인데 CONFIRM 원장이 없으면 미정산으로 센다`() {
        completedJob(withSettlementLedger = false)

        task.takeSnapshot()

        assertThat(publishedSnapshot().unsettledTerminalJobs).isEqualTo(1)
    }

    @Test
    fun `CONFIRM 원장이 있으면 미정산이 아니다`() {
        completedJob(withSettlementLedger = true)

        task.takeSnapshot()

        assertThat(publishedSnapshot().unsettledTerminalJobs).isZero()
    }

    @Test
    fun `REFUNDED 인데 REFUND 원장이 없으면 미정산으로 센다`() {
        refundedJob(withSettlementLedger = false)

        task.takeSnapshot()

        assertThat(publishedSnapshot().unsettledTerminalJobs).isEqualTo(1)
    }

    @Test
    fun `쿼리 하나가 실패하면 반쯤 채운 스냅샷을 발행하지 않는다`() {
        val brokenJobRepository: JobRepository = mock()
        whenever(brokenJobRepository.countByStatusNotIn(any())).thenReturn(1L)
        whenever(brokenJobRepository.sumHoldAmountByStatusNotIn(any())).thenReturn(100L)
        whenever(brokenJobRepository.findOldestCreatedAtByStatusNotIn(any())).thenReturn(fixedInstant)
        whenever(brokenJobRepository.countJobsWithoutHoldEntry())
            .thenThrow(IllegalStateException("DB 연결 끊김"))
        val publisher = RecordingEventPublisher()
        val brokenTask = DomainSnapshotTask(brokenJobRepository, organizationRepository, publisher, clock)

        brokenTask.takeSnapshot()

        assertThat(publisher.events).isEmpty()
    }

    private fun publishedSnapshot(): DomainSnapshotTaken =
        eventPublisher.events.filterIsInstance<DomainSnapshotTaken>().single()

    private fun holdingJob(holdAmount: Long = 100L, createdSecondsAgo: Long = 0L): Long {
        val job = jobRepository.saveAndFlush(Job.hold(ORG_ID, holdAmount, "cat"))
        if (createdSecondsAgo > 0) {
            // createdAt 은 protected set 이라 리플렉션으로 과거로 민다.
            // IdempotencyKeyCleanupTaskTest 가 쓰는 것과 같은 방법이다.
            ReflectionTestUtils.setField(job, "createdAt", fixedInstant.minusSeconds(createdSecondsAgo))
            jobRepository.saveAndFlush(job)
        }
        val jobId = job.persistedId
        ledgerRepository.saveAndFlush(LedgerEntry.hold(ORG_ID, jobId, holdAmount))
        return jobId
    }

    private fun failedJob(holdAmount: Long = 100L, createdSecondsAgo: Long = 0L): Long {
        val jobId = holdingJob(holdAmount, createdSecondsAgo)
        jobRepository.startProcessingIfAttemptMatches(jobId, 0, fixedInstant)
        jobRepository.failIfProcessing(jobId, 0, fixedInstant)
        check(jobRepository.findById(jobId).orElseThrow().status == JobStatus.FAILED)
        return jobId
    }

    private fun completedJob(withSettlementLedger: Boolean = true): Long {
        val jobId = holdingJob()
        jobRepository.startProcessingIfAttemptMatches(jobId, 0, fixedInstant)
        jobRepository.completeIfAttemptMatches(jobId, "https://example.com/result", 0, fixedInstant)
        if (withSettlementLedger) {
            ledgerRepository.saveAndFlush(LedgerEntry.confirm(ORG_ID, jobId))
        }
        return jobId
    }

    private fun refundedJob(withSettlementLedger: Boolean = true): Long {
        val jobId = failedJob()
        jobRepository.refundIfFailed(jobId, 0, fixedInstant)
        if (withSettlementLedger) {
            ledgerRepository.saveAndFlush(LedgerEntry.refund(ORG_ID, jobId, 100L))
        }
        return jobId
    }

    companion object {
        private const val ORG_ID = 1L
    }
}
