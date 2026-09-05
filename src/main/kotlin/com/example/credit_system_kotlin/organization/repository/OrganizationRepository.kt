package com.example.credit_system_kotlin.organization.repository

import com.example.credit_system_kotlin.organization.domain.Organization
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

interface OrganizationRepository : JpaRepository<Organization, Long> {

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        """
        UPDATE Organization o
        SET o.balance = o.balance - :amount, o.updatedAt = :now
        WHERE o.id = :id AND o.balance >= :amount
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
        UPDATE Organization o
        SET o.balance = o.balance + :amount, o.updatedAt = :now
        WHERE o.id = :id
        """
    )
    fun addBalance(
        @Param("id") id: Long,
        @Param("amount") amount: Long,
        @Param("now") now: Instant
    ): Int

    /** 잔액이 음수인 조직 수. 불변식이라 0 이 아니면 즉시 사고다. */
    fun countByBalanceLessThan(balance: Long): Long
}
