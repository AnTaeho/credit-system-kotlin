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

    fun findByUserIdAndIdemKey(userId: Long, idemKey: String): IdempotencyKey?

    /** hold 트랜잭션 안에서 job 을 만든 직후 부른다. 1행이 아니면 호출자가 예외로 hold 를 되돌린다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        """
        UPDATE IdempotencyKey k
        SET k.jobId = :jobId
        WHERE k.userId = :userId AND k.idemKey = :idemKey
        """
    )
    fun attachJobId(
        @Param("userId") userId: Long,
        @Param("idemKey") idemKey: String,
        @Param("jobId") jobId: Long
    ): Int

    /** 정리 대상 id 를 id 순으로 한 배치만 읽는다. */
    @Query("SELECT k.id FROM IdempotencyKey k WHERE k.createdAt < :cutoff ORDER BY k.id")
    fun findIdsCreatedBefore(@Param("cutoff") cutoff: Instant, pageable: Pageable): List<Long>

    /** 호출마다 트랜잭션이 따로라 배치 하나가 실패해도 앞서 지운 배치는 남는다. */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM IdempotencyKey k WHERE k.id IN :ids")
    fun deleteByIdIn(@Param("ids") ids: List<Long>): Int
}
