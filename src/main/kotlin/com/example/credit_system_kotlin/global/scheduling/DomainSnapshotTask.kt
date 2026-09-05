package com.example.credit_system_kotlin.global.scheduling

import com.example.credit_system_kotlin.global.event.DomainSnapshotTaken
import com.example.credit_system_kotlin.job.domain.JobStatus
import com.example.credit_system_kotlin.job.repository.JobRepository
import com.example.credit_system_kotlin.organization.repository.OrganizationRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.ApplicationEventPublisher
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration

private val log = LoggerFactory.getLogger(DomainSnapshotTask::class.java)

/**
 * 도메인 상태를 주기적으로 찍어 [DomainSnapshotTaken] 으로 발행한다.
 *
 * 카운터는 실행을 세고, 이 태스크는 상태를 잰다. 워커가 죽어서 아무 코드도 안 불리는
 * 사고는 카운터로 잡을 수 없고, 오직 "지금 이 상태인 row 가 몇 개냐"를 DB 에 직접 묻는
 * 이 경로만이 잡을 수 있다.
 *
 * `LedgerReconciliationTask` 와 같은 모양이다 — 스케줄러는 사실만 발행하고,
 * Micrometer 는 `observability` 패키지만 안다.
 */
@Component
@ConditionalOnProperty(prefix = "app.scheduling", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class DomainSnapshotTask(
    private val jobRepository: JobRepository,
    private val organizationRepository: OrganizationRepository,
    private val eventPublisher: ApplicationEventPublisher,
    private val clock: Clock
) {

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
            val negativeBalanceOrgs = organizationRepository.countByBalanceLessThan(0L)
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
                    "oldestPendingAgeSeconds={}, negativeBalanceOrgs={}, jobsWithoutHold={}, " +
                    "unsettledTerminalJobs={}",
                outstandingHoldCount, outstandingHoldAmount, oldestPendingAgeSeconds,
                negativeBalanceOrgs, jobsWithoutHold, unsettledTerminalJobs
            )
            eventPublisher.publishEvent(
                DomainSnapshotTaken(
                    outstandingHoldCount = outstandingHoldCount,
                    outstandingHoldAmount = outstandingHoldAmount,
                    oldestPendingAgeSeconds = oldestPendingAgeSeconds,
                    negativeBalanceOrgs = negativeBalanceOrgs,
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
