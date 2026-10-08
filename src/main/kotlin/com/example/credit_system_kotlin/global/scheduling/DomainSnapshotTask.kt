package com.example.credit_system_kotlin.global.scheduling

import com.example.credit_system_kotlin.global.event.DomainSnapshotTaken
import com.example.credit_system_kotlin.job.domain.JobStatus
import com.example.credit_system_kotlin.job.repository.JobRepository
import com.example.credit_system_kotlin.user.repository.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.ApplicationEventPublisher
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration

private val log = LoggerFactory.getLogger(DomainSnapshotTask::class.java)

@Component
@ConditionalOnProperty(prefix = "app.scheduling", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class DomainSnapshotTask(
    private val jobRepository: JobRepository,
    private val userRepository: UserRepository,
    private val eventPublisher: ApplicationEventPublisher,
    private val clock: Clock
) {

    @Scheduled(fixedDelayString = $$"${app.scheduling.snapshot-interval-millis:15000}")
    fun takeSnapshot() {
        val startedAt = clock.instant()
        try {
            val outstandingHoldCount = jobRepository.countByStatusNotIn(PENDING_EXCLUDED_STATUSES)
            val outstandingHoldAmount = jobRepository.sumHoldAmountByStatusNotIn(PENDING_EXCLUDED_STATUSES)
            val oldestCreatedAt = jobRepository.findOldestCreatedAtByStatusNotIn(PENDING_EXCLUDED_STATUSES)
            val negativeBalanceUsers = userRepository.countByBalanceLessThan(0L)
            val jobsWithoutHold = jobRepository.countJobsWithoutHoldEntry()
            val unsettledTerminalJobs = jobRepository.countUnsettledTerminalJobs()

            val takenAt = clock.instant()
            val oldestPendingAgeSeconds = oldestCreatedAt
                ?.let { Duration.between(it, takenAt).seconds }
                ?: 0L

            log.info(
                "도메인 스냅샷 완료: outstandingHoldCount={}, outstandingHoldAmount={}, " +
                    "oldestPendingAgeSeconds={}, negativeBalanceUsers={}, jobsWithoutHold={}, " +
                    "unsettledTerminalJobs={}",
                outstandingHoldCount, outstandingHoldAmount, oldestPendingAgeSeconds,
                negativeBalanceUsers, jobsWithoutHold, unsettledTerminalJobs
            )
            eventPublisher.publishEvent(
                DomainSnapshotTaken(
                    outstandingHoldCount = outstandingHoldCount,
                    outstandingHoldAmount = outstandingHoldAmount,
                    oldestPendingAgeSeconds = oldestPendingAgeSeconds,
                    negativeBalanceUsers = negativeBalanceUsers,
                    jobsWithoutHold = jobsWithoutHold,
                    unsettledTerminalJobs = unsettledTerminalJobs,
                    duration = Duration.between(startedAt, takenAt),
                    takenAt = takenAt
                )
            )
        } catch (e: RuntimeException) {
            log.error("도메인 스냅샷 주기 실패, 이번 주기는 발행하지 않는다", e)
        }
    }

    companion object {
        /** 미결의 정의: 종결 상태 두 개를 뺀 나머지(HOLDING, PROCESSING, FAILED)가 전부 미결이다. */
        private val PENDING_EXCLUDED_STATUSES = listOf(JobStatus.COMPLETED, JobStatus.REFUNDED)
    }
}
