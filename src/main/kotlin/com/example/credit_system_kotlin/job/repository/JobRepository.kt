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

    // HOLDING 을 PROCESSING 으로 올린다. 0행이면 상태나 시도 번호가 달라 처리를 시작하지 못한 것이다.
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        """
        UPDATE Job j
        SET j.status = JobStatus.PROCESSING, j.updatedAt = :now
        WHERE j.id = :jobId
          AND j.status = JobStatus.HOLDING
          AND j.attemptNo = :attemptNo
        """
    )
    fun startProcessingIfAttemptMatches(
        @Param("jobId") jobId: Long,
        @Param("attemptNo") attemptNo: Int,
        @Param("now") now: Instant
    ): Int

    // PROCESSING 을 FAILED 로 내린다. 0행이면 이미 끝났거나 다른 시도가 잡은 작업이라 실패로 적지 않는다.
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        """
        UPDATE Job j
        SET j.status = JobStatus.FAILED, j.updatedAt = :now
        WHERE j.id = :jobId
          AND j.status = JobStatus.PROCESSING
          AND j.attemptNo = :attemptNo
        """
    )
    fun failIfProcessing(
        @Param("jobId") jobId: Long,
        @Param("attemptNo") attemptNo: Int,
        @Param("now") now: Instant
    ): Int

    // PROCESSING 을 HOLDING 으로 되돌린다. 0행이면 그 시도가 이미 PROCESSING 을 벗어나 되돌릴 것이 없다.
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        """
        UPDATE Job j
        SET j.status = JobStatus.HOLDING, j.updatedAt = :now
        WHERE j.id = :jobId
          AND j.status = JobStatus.PROCESSING
          AND j.attemptNo = :attemptNo
        """
    )
    fun rollbackToHoldingIfProcessing(
        @Param("jobId") jobId: Long,
        @Param("attemptNo") attemptNo: Int,
        @Param("now") now: Instant
    ): Int

    // FAILED 를 REFUNDED 로 닫는다. 0행이면 다른 쪽이 먼저 환불했거나 재시도로 넘어가 환불하지 않는다.
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        """
        UPDATE Job j
        SET j.status = JobStatus.REFUNDED, j.updatedAt = :now
        WHERE j.id = :jobId
          AND j.status = JobStatus.FAILED
          AND j.attemptNo = :attemptNo
        """
    )
    fun refundIfFailed(
        @Param("jobId") jobId: Long,
        @Param("attemptNo") attemptNo: Int,
        @Param("now") now: Instant
    ): Int

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

    fun findByUserIdOrderByIdDesc(userId: Long): List<Job>

    fun findByUserIdOrderByIdDesc(userId: Long, pageable: Pageable): List<Job>

    fun findByUserIdAndIdLessThanOrderByIdDesc(userId: Long, cursor: Long, pageable: Pageable): List<Job>

    fun findByIdAndUserId(id: Long, userId: Long): Job?

    fun findByStatusAndUpdatedAtBeforeOrderByIdAsc(status: JobStatus, cutoff: Instant, pageable: Pageable): List<Job>

    fun countByStatusNotIn(statuses: Collection<JobStatus>): Long

    @Query(
        """
        SELECT COALESCE(SUM(j.holdAmount), 0L) FROM Job j
        WHERE j.status NOT IN :statuses
        """
    )
    fun sumHoldAmountByStatusNotIn(@Param("statuses") statuses: Collection<JobStatus>): Long

    @Query(
        """
        SELECT MIN(j.createdAt) FROM Job j
        WHERE j.status NOT IN :statuses
        """
    )
    fun findOldestCreatedAtByStatusNotIn(@Param("statuses") statuses: Collection<JobStatus>): Instant?

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
