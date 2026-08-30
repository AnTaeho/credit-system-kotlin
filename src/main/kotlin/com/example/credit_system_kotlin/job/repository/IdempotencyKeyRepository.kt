package com.example.credit_system_kotlin.job.repository

import com.example.credit_system_kotlin.job.domain.IdempotencyKey
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

interface IdempotencyKeyRepository : JpaRepository<IdempotencyKey, Long> {

    fun findByOrganizationIdAndIdemKey(organizationId: Long, idemKey: String): IdempotencyKey?

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        """
        UPDATE IdempotencyKey k
        SET k.jobId = :jobId
        WHERE k.organizationId = :organizationId AND k.idemKey = :idemKey
        """
    )
    fun attachJobId(
        @Param("organizationId") organizationId: Long,
        @Param("idemKey") idemKey: String,
        @Param("jobId") jobId: Long
    ): Int

    @Query("SELECT k.id FROM IdempotencyKey k WHERE k.createdAt < :cutoff ORDER BY k.id")
    fun findIdsCreatedBefore(@Param("cutoff") cutoff: Instant, pageable: Pageable): List<Long>

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM IdempotencyKey k WHERE k.id IN :ids")
    fun deleteByIdIn(@Param("ids") ids: List<Long>): Int
}
