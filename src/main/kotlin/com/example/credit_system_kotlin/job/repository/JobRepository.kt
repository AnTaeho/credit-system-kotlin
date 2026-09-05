package com.example.credit_system_kotlin.job.repository

import com.example.credit_system_kotlin.job.domain.Job
import com.example.credit_system_kotlin.job.domain.JobStatus
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

interface JobRepository : JpaRepository<Job, Long> {

    fun startProcessingIfAttemptMatches(jobId: Long, attemptNo: Int, now: Instant): Int =
        transitionIfStatusAndAttemptMatch(jobId, JobStatus.PROCESSING, JobStatus.HOLDING, attemptNo, now)

    /** PROCESSING 인 시도만 FAILED 로 내린다. */
    fun failIfProcessing(jobId: Long, attemptNo: Int, now: Instant): Int =
        transitionIfStatusAndAttemptMatch(jobId, JobStatus.FAILED, JobStatus.PROCESSING, attemptNo, now)

    /** 선점을 되돌린다. PROCESSING 인 시도만 HOLDING 으로 돌린다. */
    fun rollbackToHoldingIfProcessing(jobId: Long, attemptNo: Int, now: Instant): Int =
        transitionIfStatusAndAttemptMatch(jobId, JobStatus.HOLDING, JobStatus.PROCESSING, attemptNo, now)

    /** FAILED 인 시도만 REFUNDED 로 내린다. */
    fun refundIfFailed(jobId: Long, attemptNo: Int, now: Instant): Int =
        transitionIfStatusAndAttemptMatch(jobId, JobStatus.REFUNDED, JobStatus.FAILED, attemptNo, now)

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        """
        UPDATE Job j
        SET j.status = JobStatus.COMPLETED,
            j.resultUrl = :resultUrl, j.updatedAt = :now
        WHERE j.id = :jobId
          AND j.status = JobStatus.PROCESSING
          AND j.attemptNo = :attemptNo
        """
    )
    fun completeIfAttemptMatches(
        @Param("jobId") jobId: Long,
        @Param("resultUrl") resultUrl: String,
        @Param("attemptNo") attemptNo: Int,
        @Param("now") now: Instant
    ): Int

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        """
        UPDATE Job j
        SET j.status = :newStatus, j.updatedAt = :now
        WHERE j.id = :jobId AND j.status = :expectedStatus AND j.attemptNo = :attemptNo
        """
    )
    fun transitionIfStatusAndAttemptMatch(
        @Param("jobId") jobId: Long,
        @Param("newStatus") newStatus: JobStatus,
        @Param("expectedStatus") expectedStatus: JobStatus,
        @Param("attemptNo") attemptNo: Int,
        @Param("now") now: Instant
    ): Int

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        """
        UPDATE Job j
        SET j.attemptNo = j.attemptNo + 1,
            j.status = JobStatus.HOLDING,
            j.updatedAt = :now
        WHERE j.id = :jobId
          AND j.status = JobStatus.FAILED
          AND j.attemptNo = :expectedAttemptNo
        """
    )
    fun incrementAttemptForRetry(
        @Param("jobId") jobId: Long,
        @Param("expectedAttemptNo") expectedAttemptNo: Int,
        @Param("now") now: Instant
    ): Int

    fun findByStatusOrderByIdAsc(status: JobStatus, pageable: Pageable): List<Job>

    fun findByOrganizationIdOrderByIdDesc(organizationId: Long): List<Job>

    fun findByStatusAndUpdatedAtBeforeOrderByIdAsc(status: JobStatus, cutoff: Instant, pageable: Pageable): List<Job>

    /**
     * 미결 job 수. 미결은 `status NOT IN (COMPLETED, REFUNDED)` 로, FAILED 도 포함한다 —
     * 재시도나 환불을 기다리는 중이고 돈이 아직 묶여 있기 때문이다.
     */
    fun countByStatusNotIn(statuses: Collection<JobStatus>): Long

    /** 미결 job 에 묶여 있는 홀드 금액의 합. 미결이 없으면 0 이다. */
    @Query(
        """
        SELECT COALESCE(SUM(j.holdAmount), 0L) FROM Job j
        WHERE j.status NOT IN :statuses
        """
    )
    fun sumHoldAmountByStatusNotIn(@Param("statuses") statuses: Collection<JobStatus>): Long

    /**
     * 가장 오래된 미결 job 의 생성 시각. 미결이 없으면 null 이다.
     *
     * `updatedAt` 이 아니라 `createdAt` 을 본다. 재시도로 상태가 바뀌어도 그 job 의 돈은
     * 처음부터 계속 묶여 있으므로, "묶인 시간"의 기준점은 생성 시각이어야 한다.
     */
    @Query(
        """
        SELECT MIN(j.createdAt) FROM Job j
        WHERE j.status NOT IN :statuses
        """
    )
    fun findOldestCreatedAtByStatusNotIn(@Param("statuses") statuses: Collection<JobStatus>): Instant?

    /** HOLD 원장 없이 존재하는 job 수. 불변식이라 0 이어야 한다. */
    @Query(
        """
        SELECT COUNT(j) FROM Job j
        WHERE NOT EXISTS (
            SELECT 1 FROM LedgerEntry l
            WHERE l.jobId = j.id AND l.type = LedgerType.HOLD
        )
        """
    )
    fun countJobsWithoutHoldEntry(): Long

    /**
     * 종결 상태인데 정산 원장이 없는 job 수. COMPLETED 인데 CONFIRM 이 없거나,
     * REFUNDED 인데 REFUND 가 없는 경우다. 불변식이라 0 이어야 한다.
     */
    @Query(
        """
        SELECT COUNT(j) FROM Job j
        WHERE (
            j.status = JobStatus.COMPLETED
            AND NOT EXISTS (
                SELECT 1 FROM LedgerEntry l
                WHERE l.jobId = j.id AND l.type = LedgerType.CONFIRM
            )
        ) OR (
            j.status = JobStatus.REFUNDED
            AND NOT EXISTS (
                SELECT 1 FROM LedgerEntry l
                WHERE l.jobId = j.id AND l.type = LedgerType.REFUND
            )
        )
        """
    )
    fun countUnsettledTerminalJobs(): Long
}
