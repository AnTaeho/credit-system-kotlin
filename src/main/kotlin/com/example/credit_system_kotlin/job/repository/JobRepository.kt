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

    fun findByStatusOrderByIdAsc(status: JobStatus, pageable: Pageable): List<Job>

    fun findByOrganizationIdOrderByIdDesc(organizationId: Long): List<Job>
}
