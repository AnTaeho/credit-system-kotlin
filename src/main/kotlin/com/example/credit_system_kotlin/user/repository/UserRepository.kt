package com.example.credit_system_kotlin.user.repository

import com.example.credit_system_kotlin.user.domain.User
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
}
