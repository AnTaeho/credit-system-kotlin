package com.example.credit_system_kotlin.ledger

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface LedgerRepository : JpaRepository<LedgerEntry, Long> {

    fun findByOrganizationIdOrderByIdDesc(organizationId: Long): List<LedgerEntry>

    fun findByOrganizationIdAndIdemKey(organizationId: Long, idemKey: String): LedgerEntry?

    @Query(
        """
        SELECT new com.example.credit_system_kotlin.ledger.LedgerBalanceCheck(
            o.id, o.balance, o.initialBalance, COALESCE(SUM(l.amount), 0L))
        FROM Organization o
        LEFT JOIN LedgerEntry l ON l.organizationId = o.id
        WHERE o.id > :lastId
        GROUP BY o.id, o.balance, o.initialBalance
        ORDER BY o.id
        """
    )
    fun findBalanceChecksAfter(@Param("lastId") lastId: Long, pageable: Pageable): List<LedgerBalanceCheck>
}
