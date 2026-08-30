package com.example.credit_system_kotlin.ledger.service

import com.example.credit_system_kotlin.ledger.dto.LedgerResponse
import com.example.credit_system_kotlin.ledger.repository.LedgerRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class LedgerQueryService(private val ledgerRepository: LedgerRepository) {

    @Transactional(readOnly = true)
    fun findByOrganization(organizationId: Long): List<LedgerResponse> =
        ledgerRepository.findByOrganizationIdOrderByIdDesc(organizationId)
            .map(LedgerResponse::from)
}
