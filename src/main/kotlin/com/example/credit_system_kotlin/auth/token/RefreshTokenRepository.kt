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

    /** 로그아웃한 토큰 한 장을 지운다. 지운 행 수를 돌려준다. 모르는 토큰이면 0 이다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM RefreshToken t WHERE t.tokenHash = :tokenHash")
    fun deleteByTokenHash(@Param("tokenHash") tokenHash: String): Int

    /** 한 사용자의 토큰을 모두 지운다. 비밀번호나 역할이 밖에서 바뀌었을 때 이미 나간 로그인을 끊는다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM RefreshToken t WHERE t.userId = :userId")
    fun deleteAllOfUser(@Param("userId") userId: Long): Int

    /** 정리 작업이 한 묶음씩 지울 id 를 고른다. 매번 첫 페이지를 다시 읽으므로 지운 만큼 다음 묶음이 올라온다. */
    @Query("SELECT t.id FROM RefreshToken t WHERE t.expiresAt < :cutoff ORDER BY t.id")
    fun findIdsExpiredBefore(@Param("cutoff") cutoff: Instant, pageable: Pageable): List<Long>

    /** 정리 작업에 트랜잭션이 없어 여기서 연다. 묶음마다 따로 커밋된다. */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM RefreshToken t WHERE t.id IN :ids")
    fun deleteByIdIn(@Param("ids") ids: List<Long>): Int
}
