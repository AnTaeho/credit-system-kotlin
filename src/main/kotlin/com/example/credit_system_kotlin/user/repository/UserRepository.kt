package com.example.credit_system_kotlin.user.repository

import com.example.credit_system_kotlin.user.domain.User
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

interface UserRepository : JpaRepository<User, Long> {

    /**
     * 잔액이 [amount] 이상일 때만 깎아서 동시 요청에도 음수가 되지 않는다. 0행이면 잔액 부족이거나 없는 사용자다.
     * 실행 뒤 영속성 컨텍스트를 비우므로 먼저 읽어 둔 `User` 의 잔액은 낡은 값이다. 필요하면 다시 읽는다.
     */
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

    /** 조건 없이 더한다. 0행이면 없는 사용자다. [deductBalance] 처럼 실행 뒤 영속성 컨텍스트를 비운다. */
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

    fun findByEmail(email: String): User?

    /** 잔액이 음수인 사용자 수. 불변식이라 0 이 아니면 즉시 사고다. */
    fun countByBalanceLessThan(balance: Long): Long
}
