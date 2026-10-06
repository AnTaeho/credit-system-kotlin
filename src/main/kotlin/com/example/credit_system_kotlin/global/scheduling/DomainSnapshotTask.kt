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

/**
 * 주기마다 DB 에 미결 job 과 불변식 위반 건수를 물어 [DomainSnapshotTaken] 으로 발행한다.
 * 워커가 죽어 아무 코드도 안 불리면 카운터는 조용하고 이 값만 움직인다.
 */
@Component
@ConditionalOnProperty(prefix = "app.scheduling", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class DomainSnapshotTask(
    private val jobRepository: JobRepository,
    private val userRepository: UserRepository,
    private val eventPublisher: ApplicationEventPublisher,
    private val clock: Clock
) {

    /** 쿼리가 하나라도 실패하면 이번 주기는 발행하지 않고 다음 주기에 다시 찍는다. */
    @Scheduled(fixedDelayString = $$"${app.scheduling.snapshot-interval-millis:15000}")
    fun takeSnapshot() {
        val startedAt = clock.instant()
        try {
            // 쿼리 하나가 실패하면 주기 전체를 버린다. 스냅샷은 한 시점의 일관된 그림이어야
            // 하고, 반쯤 채운 스냅샷은 "미결이 0건이다" 같은 거짓말을 하게 된다.
            // 항목 단위 try/catch 로 격리하는 대사 태스크와 여기서 갈린다.
            val outstandingHoldCount = jobRepository.countByStatusNotIn(PENDING_EXCLUDED_STATUSES)
            val outstandingHoldAmount = jobRepository.sumHoldAmountByStatusNotIn(PENDING_EXCLUDED_STATUSES)
            val oldestCreatedAt = jobRepository.findOldestCreatedAtByStatusNotIn(PENDING_EXCLUDED_STATUSES)
            val negativeBalanceUsers = userRepository.countByBalanceLessThan(0L)
            val jobsWithoutHold = jobRepository.countJobsWithoutHoldEntry()
            val unsettledTerminalJobs = jobRepository.countUnsettledTerminalJobs()

            val takenAt = clock.instant()
            // 미결이 없으면 0 이다. 여기서 0 은 "묶인 돈이 없다" = 정상이라 모호하지 않다.
            // 대사 staleness 의 -1 과 다른 점이다 — 그쪽 0 은 "방금 성공"과 구분이 안 된다.
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
            // 삼켜서 다음 주기가 계속 돌게 한다. 이 주기의 이벤트는 발행되지 않으므로
            // staleness 게이지가 대신 올라 "스냅샷이 멈췄다"를 드러낸다.
            log.error("도메인 스냅샷 주기 실패, 이번 주기는 발행하지 않는다", e)
        }
    }

    companion object {
        /** 미결의 정의: 종결 상태 두 개를 뺀 나머지(HOLDING, PROCESSING, FAILED)가 전부 미결이다. */
        private val PENDING_EXCLUDED_STATUSES = listOf(JobStatus.COMPLETED, JobStatus.REFUNDED)
    }
}
