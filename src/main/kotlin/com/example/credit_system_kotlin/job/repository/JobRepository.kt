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

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        """
        UPDATE Job j
        SET j.status = com.example.credit_system_kotlin.job.domain.JobStatus.PROCESSING,
            j.updatedAt = :now
        WHERE j.id = :jobId
          AND j.status = com.example.credit_system_kotlin.job.domain.JobStatus.HOLDING
          AND j.attemptNo = :attemptNo
        """
    )
    fun startProcessingIfAttemptMatches(
        @Param("jobId") jobId: Long,
        @Param("attemptNo") attemptNo: Int,
        @Param("now") now: Instant
    ): Int

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        """
        UPDATE Job j
        SET j.status = com.example.credit_system_kotlin.job.domain.JobStatus.COMPLETED,
            j.resultUrl = :resultUrl, j.updatedAt = :now
        WHERE j.id = :jobId
          AND j.status = com.example.credit_system_kotlin.job.domain.JobStatus.PROCESSING
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
            j.status = com.example.credit_system_kotlin.job.domain.JobStatus.HOLDING,
            j.updatedAt = :now
        WHERE j.id = :jobId
          AND j.status = com.example.credit_system_kotlin.job.domain.JobStatus.FAILED
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
}
