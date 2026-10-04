package com.example.credit_system_kotlin.auth.token

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

interface RefreshTokenRepository : JpaRepository<RefreshToken, Long> {

    fun findByTokenHash(tokenHash: String): RefreshToken?

    /**
     * 아직 쓰이지 않았고 폐기·만료되지 않은 토큰만 "회전됨"으로 바꾼다. 1 이면 이 요청이 회전을 차지한 것이고,
     * 0 이면 다른 요청이 먼저 가져갔거나 쓸 수 없는 토큰이다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        """
        UPDATE RefreshToken t
        SET t.rotatedAt = :now, t.updatedAt = :now
        WHERE t.tokenHash = :tokenHash
          AND t.rotatedAt IS NULL
          AND t.revokedAt IS NULL
          AND t.expiresAt > :now
        """
    )
    fun markRotated(@Param("tokenHash") tokenHash: String, @Param("now") now: Instant): Int

    /** 사슬 전체를 폐기한다. 이미 폐기된 행은 처음 폐기된 시각을 지킨다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        """
        UPDATE RefreshToken t
        SET t.revokedAt = :now, t.updatedAt = :now
        WHERE t.familyId = :familyId AND t.revokedAt IS NULL
        """
    )
    fun revokeFamily(@Param("familyId") familyId: String, @Param("now") now: Instant): Int

    @Query("SELECT t.id FROM RefreshToken t WHERE t.expiresAt < :cutoff ORDER BY t.id")
    fun findIdsExpiredBefore(@Param("cutoff") cutoff: Instant, pageable: Pageable): List<Long>

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM RefreshToken t WHERE t.id IN :ids")
    fun deleteByIdIn(@Param("ids") ids: List<Long>): Int
}
