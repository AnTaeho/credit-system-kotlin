package com.example.credit_system_kotlin.user.repository

import com.example.credit_system_kotlin.user.domain.User
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

interface UserRepository : JpaRepository<User, Long> {

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        """
        UPDATE User u
        SET u.balance = u.balance - :amount, u.updatedAt = :now
        WHERE u.id = :id AND u.balance >= :amount
        """
    )
    fun deductBalance(
        @Param("id") id: Long,
        @Param("amount") amount: Long,
        @Param("now") now: Instant
    ): Int

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        """
        UPDATE User u
        SET u.balance = u.balance + :amount, u.updatedAt = :now
        WHERE u.id = :id
        """
    )
    fun addBalance(
        @Param("id") id: Long,
        @Param("amount") amount: Long,
        @Param("now") now: Instant
    ): Int

    fun findByGoogleSub(googleSub: String): User?

    fun findByEmail(email: String): User?

    /** 잔액이 음수인 사용자 수. 불변식이라 0 이 아니면 즉시 사고다. */
    fun countByBalanceLessThan(balance: Long): Long

    /**
     * 대사 배치 1단계: 검사할 사용자 id 한 페이지만 읽는다.
     *
     * PK 범위 스캔이라 싸다. 집계(`GROUP BY`)가 없으니 `LIMIT` 이 스캔 자체를 끊는다 —
     * 이것이 `LedgerRepository.findBalanceChecksFor` 와 짝을 이루는 두 단계의 앞쪽이다.
     * 왜 두 단계인지는 그 메서드의 주석을 보라.
     */
    @Query(
        """
        SELECT u.id FROM User u
        WHERE u.id > :lastId
        ORDER BY u.id
        """
    )
    fun findIdsAfter(@Param("lastId") lastId: Long, pageable: Pageable): List<Long>
}
