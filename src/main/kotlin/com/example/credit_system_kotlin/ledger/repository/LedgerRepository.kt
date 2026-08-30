package com.example.credit_system_kotlin.ledger.repository

import com.example.credit_system_kotlin.ledger.domain.LedgerEntry
import org.springframework.data.jpa.repository.JpaRepository

interface LedgerRepository : JpaRepository<LedgerEntry, Long> {

    fun findByOrganizationIdOrderByIdDesc(organizationId: Long): List<LedgerEntry>
}
